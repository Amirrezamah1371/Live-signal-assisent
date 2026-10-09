package com.example.livesignalassistant

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.Inflater
import kotlin.math.max

/**
 * Replays logged traces through ChangeEngine, SignalAnalyzer and CycleDecider.
 * Outcome labels are read only to score a finished run. The test does not assert a win rate.
 * Holdout sessions are skipped unless lsa.replay.holdout=1, so a normal test run cannot tune on them.
 */
class FieldReplayTest {
    @Test
    fun replayDevelopmentSessionsWhenEnabled() {
        assumeTrue("development replay disabled", System.getProperty("lsa.replay.dev") == "1")
        val root = File(System.getProperty("lsa.replay.root").ifEmpty { "/tmp/lsa7203/extract/sessions" })
        val sessions = root.listFiles()?.filter { it.isDirectory && it.name.startsWith("20261008") }?.sortedBy { it.name }
            ?: emptyList()
        assertTrue("no October 8 sessions under $root", sessions.isNotEmpty())
        val report = sessions.joinToString("\n\n") { replay(it) }
        File("/tmp/lsa_replay_dev.txt").writeText(report)
        println(report)
    }

    @Test
    fun replayHoldoutSessionWhenEnabled() {
        assumeTrue("holdout replay disabled", System.getProperty("lsa.replay.holdout") == "1")
        val root = File(System.getProperty("lsa.replay.root").ifEmpty { "/tmp/lsa7203/extract/sessions" })
        val session = File(root, "20261009_022815")
        assertTrue(session.isDirectory)
        val report = replay(session)
        File("/tmp/lsa_replay_holdout.txt").writeText(report)
        println(report)
    }

    private fun replay(session: File): String {
        val timeline = File(session, "timeline.jsonl")
        val engine = ChangeEngine()
        val observations = ArrayList<Obs>()
        val loggedObs = ArrayList<Obs>()
        var lastT = 0.0
        var conflicts = 0
        var updates = 0
        var inversions = 0
        var prevTip = Double.NaN
        var prevRef = Double.NaN
        val logged = ArrayList<Pair<Int, String>>()
        val resultByCycle = HashMap<Int, String>()
        val signalCycle = HashMap<String, Int>()
        var lastDecisionCycle: Int? = null
        var lastObservationTs = 0L
        val replayDir = HashMap<Int, String>()
        val replayReason = HashMap<Int, String>()
        val loggedVoteDir = HashMap<Int, String>()
        val loggedVoteReason = HashMap<Int, String>()
        val replayNote = HashMap<Int, String>()
        var cycles = 0
        var firstTs = 0L
        var lastTs = 0L
        timeline.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val o = JSONObject(line)
                val ts = o.optLong("ts_ms")
                if (firstTs == 0L) firstTs = ts
                lastTs = ts
                when (o.getString("type")) {
                    "BOT_OBSERVATION" -> {
                        lastObservationTs = ts
                        val elapsed = o.optLong("cycle_elapsed_ms")
                        val d = o.optJSONObject("diagnostics")
                        val decoded = d?.optString("trace_b64")?.let { decodeTrace(it) }
                        var vel = 0.0
                        var acc = 0.0
                        var tr: TraceResult? = null
                        if (decoded != null) {
                            val (ys, real) = decoded
                            val connected = TraceGeometry.adoptConnectedTip(ys, real)
                            tr = traceOf(ys, real, connected)
                            if (tr.tipConnected && tr.tipGapFrac <= 0.25 && tr.realFrac >= 0.30 && tr.ys.size >= 72) {
                                val path = DoubleArray(ys.size) { -ys[it] }
                                val tip = tail(path, real)
                                val info = engine.update(ts / 1000.0, path, real)
                                lastT = ts / 1000.0
                                updates++
                                val ref = (info["ref_v"] as Number).toDouble()
                                if (!prevTip.isNaN() && info["reg_status"] == "OK") {
                                    val rawMove = tip - prevTip
                                    val regMove = ref - prevRef
                                    if (kotlin.math.abs(rawMove) > 8.0 && kotlin.math.abs(regMove) > 8.0 && rawMove * regMove < 0.0) {
                                        inversions++
                                    }
                                }
                                if (info["reg_status"] == "OK") {
                                    prevTip = tip
                                    prevRef = ref
                                }
                                val k = engine.kinematicsPx(lastT)
                                if (k != null) {
                                    vel = k.first
                                    acc = k.second
                                }
                            }
                        }
                        val w = d?.optInt("bmp_w") ?: 1080
                        val h = d?.optInt("bmp_h") ?: 2400
                        val signal = SignalAnalyzer.analyze(w, h, tr, vel, acc)
                        observations += Obs(elapsed, signal)
                        loggedObs += Obs(elapsed, loggedSignal(o))
                    }
                    "NINETY_SECOND_DECISION" -> {
                        val cycle = o.optInt("cycle")
                        lastDecisionCycle = cycle
                        val loggedDir = o.optString("direction")
                        logged += Pair(cycle, loggedDir)
                        val decisionT = ts / 1000.0
                        val staleMs = if (lastObservationTs == 0L) Long.MAX_VALUE else (ts - lastObservationTs).coerceAtLeast(0L)
                        val decision = CycleDecider.decide(observations.toList(), 90000L, engine, decisionT, quiet(), staleMs)
                        val fromLog = CycleDecider.decide(loggedObs.toList(), 90000L, engine, decisionT, quiet(), staleMs)
                        cycles++
                        replayDir[cycle] = decision.direction
                        replayReason[cycle] = decision.reason
                        loggedVoteDir[cycle] = fromLog.direction
                        loggedVoteReason[cycle] = fromLog.reason
                        if (decision.diagnostics["channel_disagree"] == true) conflicts++
                        fun num(k: String): String {
                            val v = decision.diagnostics[k]
                            return if (v is Number) "%.2f".format(v.toDouble()) else v?.toString() ?: "-"
                        }
                        replayNote[cycle] = "z3=${num("slope_3s")} z6=${num("slope_6s")} z10=${num("slope_10s")} z20=${num("slope_20s")} agree=${num("window_agreement")} state=${num("final_state")} recent=${num("recent_invalid_reason")} pts=${num("series_points")} span=${num("series_span_s")} dirN=${num("path_usable")} leg=${num("local_leg")} net=${num("local_net")} rel=${num("vision_reliability")} path=${num("path_confirmed")}"
                        observations.clear()
                        loggedObs.clear()
                        lastObservationTs = 0L
                        SignalAnalyzer.reset()
                    }
                    "SIGNAL_PUBLISHED" -> {
                        val id = o.optString("signal_id")
                        val cycle = lastDecisionCycle
                        if (id.isNotEmpty() && cycle != null) signalCycle[id] = cycle
                    }
                    "TRADE_RESULT" -> {
                        val cycle = signalCycle[o.optString("signal_id")]
                        if (cycle != null) resultByCycle[cycle] = o.optString("result")
                    }
                }
            }
        }
        val labeled = ArrayList<String>()
        val every = ArrayList<String>()
        var blockedWins = 0
        var blockedLosses = 0
        var sameWins = 0
        var sameLosses = 0
        var opposite = 0
        var replaySignals = 0
        var loggedVoteSignals = 0
        var unlabeledSignals = 0
        for ((cycle, loggedDir) in logged) {
            val replayed = replayDir[cycle] ?: "WAIT"
            every += "c$cycle log=$loggedDir replay=$replayed ${replayReason[cycle]} votes=${loggedVoteDir[cycle]}/${loggedVoteReason[cycle]} ${replayNote[cycle]}"
            if (replayed != "WAIT") replaySignals++
            if ((loggedVoteDir[cycle] ?: "WAIT") != "WAIT") loggedVoteSignals++
            if (loggedDir == "WAIT") {
                if (replayed != "WAIT") unlabeledSignals++
                continue
            }
            val outcome = resultByCycle[cycle] ?: continue
            labeled += "cycle $cycle logged $loggedDir replay $replayed ${replayReason[cycle]} votes ${loggedVoteDir[cycle]} ${loggedVoteReason[cycle]} $outcome ${replayNote[cycle]}"
            when {
                replayed == "WAIT" && outcome == "WIN" -> blockedWins++
                replayed == "WAIT" && outcome == "LOSS" -> blockedLosses++
                replayed == loggedDir && outcome == "WIN" -> sameWins++
                replayed == loggedDir && outcome == "LOSS" -> sameLosses++
                replayed != "WAIT" && replayed != loggedDir -> opposite++
            }
        }
        val hours = max(1e-6, (lastTs - firstTs) / 3_600_000.0)
        val reasons = replayReason.values.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
        return buildString {
            appendLine("session ${session.name}")
            appendLine("cycles $cycles")
            appendLine("replay_signals $replaySignals")
            appendLine("logged_vote_signals $loggedVoteSignals")
            appendLine("signals_per_hour ${"%.2f".format(replaySignals / hours)}")
            appendLine("hours ${"%.2f".format(hours)}")
            appendLine("updates $updates inversions $inversions channel_conflicts $conflicts")
            appendLine("same_wins $sameWins same_losses $sameLosses")
            appendLine("blocked_wins $blockedWins blocked_losses $blockedLosses")
            appendLine("opposite_direction $opposite unlabeled_new_signals $unlabeledSignals")
            appendLine("reasons $reasons")
            appendLine(labeled.joinToString("\n"))
            appendLine("--cycles--")
            appendLine(every.joinToString("\n"))
        }
    }

    private fun loggedSignal(o: JSONObject): SignalResult {
        val dir = o.optString("direction", "WAIT")
        val reason = o.optString("reason", "")
        val side = o.optInt("side", if (dir == "UP") 1 else if (dir == "DOWN") -1 else 0)
        val d = o.optJSONObject("diagnostics")
        val exhaustion = d?.optDouble("exhaustion", 0.0) ?: 0.0
        val regime = d?.optDouble("trend_regime", 0.5) ?: 0.5
        return SignalResult(
            dir, 60, o.optInt("raw_confidence", 0), o.optInt("entry", 0), o.optInt("conflict", 50), reason,
            diagnostics = mapOf("exhaustion" to exhaustion, "trend_regime" to regime),
            side = side, traceQuality = d?.optDouble("trace_q", 0.8) ?: 0.8
        )
    }

    private fun quiet() = object : ExperienceSource {
        override fun advise(action: String, state: String, regime: String, band: String) =
            ExperienceStore.Advice("NONE", 0.0, 0.0, 0.5, 0.0, 1.0, -1, "", "REPLAY", 0.5)
    }

    private fun tail(path: DoubleArray, real: BooleanArray): Double {
        val vals = ArrayList<Double>(3)
        for (i in path.lastIndex downTo 0) {
            if (i < real.size && real[i]) vals += path[i]
            if (vals.size == 3) break
        }
        if (vals.isEmpty()) return path.last()
        return vals.sorted()[vals.size / 2]
    }

    private fun traceOf(ys: DoubleArray, real: BooleanArray, tipConnected: Boolean = true): TraceResult {
        var hi = -1
        var nReal = 0
        for (i in ys.indices) if (i < real.size && real[i]) {
            hi = i
            nReal++
        }
        val realFrac = if (ys.isEmpty()) 0.0 else nReal.toDouble() / ys.size
        val tipGap = if (hi < 0 || ys.isEmpty()) 1.0 else (ys.size - 1 - hi).toDouble() / ys.size
        return TraceResult(ys, real, realFrac, 0.0, 0.0, true, 0, 1, 0.8, 2, tipGap, tipConnected)
    }

    private fun decodeTrace(b64: String): Pair<DoubleArray, BooleanArray>? {
        if (b64.isEmpty()) return null
        val inf = Inflater()
        inf.setInput(Base64.getDecoder().decode(b64))
        val rawOut = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) break
                rawOut.write(buf, 0, n)
            }
        } finally {
            inf.end()
        }
        val raw = rawOut.toByteArray()
        if (raw.size < 2) return null
        val n = ((raw[0].toInt() and 255) shl 8) or (raw[1].toInt() and 255)
        if (raw.size < 2 + 2 * n) return null
        val ys = DoubleArray(n) { i ->
            (((raw[2 + 2 * i].toInt() and 255) shl 8) or (raw[3 + 2 * i].toInt() and 255)).toDouble()
        }
        val base = 2 + 2 * n
        val real = BooleanArray(n) { i ->
            base + i / 8 < raw.size && (raw[base + i / 8].toInt() shr (i % 8) and 1) == 1
        }
        return Pair(ys, real)
    }
}

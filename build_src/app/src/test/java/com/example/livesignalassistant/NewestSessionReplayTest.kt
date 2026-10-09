package com.example.livesignalassistant

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * Replays the newest real sessions through the structural wave gate.
 * Logged SIGNAL_PUBLISHED rows are the before-repair behavior.
 * The replay does not read WIN/LOSS and does not load ExperienceStore.
 */
class NewestSessionReplayTest {
    @Test
    fun replayNewestRealSessions() {
        val files = listOf(
            File("/home/ubuntu/.cursor/projects/workspace/uploads/REALTEST_20261009_215606_FULL_TELEMETRY_8997.txt"),
            File("/home/ubuntu/.cursor/projects/workspace/uploads/REALTEST_20261009_203722_FULL_TELEMETRY_5afc.txt"),
            File("/home/ubuntu/.cursor/projects/workspace/uploads/REALTEST_20261009_202003_FULL_TELEMETRY_fc5b.txt"),
            File("/home/ubuntu/.cursor/projects/workspace/uploads/REALTEST_20261009_183438_FULL_TELEMETRY_65a7.txt")
        )
        assumeTrue("newest telemetry is not in this environment", files.all { it.isFile })
        val report = files.joinToString("\n\n") { replay(it) }
        val out = File("/opt/cursor/artifacts/newest_session_replay.txt")
        out.parentFile?.mkdirs()
        out.writeText(report)
        println(report)
        assertTrue(report.contains("SESSION"))
    }

    private class Snap(
        val ts: Long,
        val analyzerDir: String,
        val analyzerReason: String,
        val evalDir: String,
        val evalReason: String,
        val wave: WaveRead,
        val leg: Double,
        val vel: Double,
        val acc: Double,
        val ref: Double,
        val reg: String
    )

    private class Pub(val ts: Long, val dir: String, val reason: String, val shock: Boolean)

    private fun replay(file: File): String {
        val engine = ChangeEngine()
        val opportunity = OpportunityCycle()
        val windowObs = ArrayList<Obs>()
        val snaps = ArrayList<Snap>()
        val afterPubs = ArrayList<Pub>()
        val beforePubs = ArrayList<Pub>()
        var beforeWaitObs = 0
        var beforeUpObs = 0
        var beforeDownObs = 0
        var afterWaitObs = 0
        var afterUpObs = 0
        var afterDownObs = 0
        var before90Wait = 0
        var before90Signal = 0
        var after90Wait = 0
        var after90Signal = 0
        var structuralPullback = 0
        var structuralShock = 0
        var structuralUnstable = 0
        var started = false
        var lastObsTs = 0L
        var bubbleCloseTs = Long.MAX_VALUE
        SignalAnalyzer.reset()

        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val o = JSONObject(line)
                val ts = o.optLong("ts_ms")
                when (o.getString("type")) {
                    "SIGNAL_PUBLISHED" -> {
                        val d = o.optJSONObject("diagnostics")
                        val setup = d?.optString("setup_key").orEmpty()
                        val reason = setup.split("|").getOrNull(1).orEmpty()
                        beforePubs += Pub(ts, o.optString("direction"), reason, false)
                    }
                    "NINETY_SECOND_DECISION" -> {
                        if (o.optString("direction") == "WAIT") before90Wait++ else before90Signal++
                    }
                    "BOT_OBSERVATION" -> {
                        if (!started) {
                            opportunity.start(ts)
                            started = true
                        }
                        closeWindowIfDue(ts, opportunity, windowObs, engine, afterPubs, { after90Wait++; }, { after90Signal++ })
                        if (opportunity.bubbleOpen && ts >= bubbleCloseTs) opportunity.onBubbleTimeout()
                        val loggedDir = o.optString("direction", "WAIT")
                        when (loggedDir) {
                            "UP" -> beforeUpObs++
                            "DOWN" -> beforeDownObs++
                            else -> beforeWaitObs++
                        }
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
                                engine.update(ts / 1000.0, path, real)
                                val k = engine.kinematicsPx(ts / 1000.0)
                                if (k != null) {
                                    vel = k.first
                                    acc = k.second
                                }
                            }
                        }
                        val w = d?.optInt("bmp_w") ?: 1080
                        val h = d?.optInt("bmp_h") ?: 2400
                        val analyzed = SignalAnalyzer.analyze(w, h, tr, vel, acc)
                        when (analyzed.direction) {
                            "UP" -> afterUpObs++
                            "DOWN" -> afterDownObs++
                            else -> afterWaitObs++
                        }
                        val elapsed = (ts - opportunity.startMs).coerceAtLeast(0L)
                        windowObs += Obs(elapsed, analyzed)
                        lastObsTs = ts
                        val tSec = ts / 1000.0
                        val eval = OpportunityDecider.evaluate(analyzed, engine, tSec, quiet())
                        when (eval.reason) {
                            "PULLBACK" -> structuralPullback++
                            "SHOCK_UNRESOLVED" -> structuralShock++
                            "STRUCTURE_UNSTABLE" -> structuralUnstable++
                        }
                        val wave = engine.wave(tSec)
                        val leg = engine.localMotion().leg
                        val ref = (engine.debugState()["last"] as? Number)?.toDouble() ?: Double.NaN
                        snaps += Snap(
                            ts, analyzed.direction, analyzed.reason, eval.direction, eval.reason,
                            wave, leg, vel, acc, ref, engine.lastReg
                        )
                        val published = opportunity.consider(analyzed, engine, tSec, quiet())
                        if (published != null) {
                            val shock = published.diagnostics["wave_shock"] == true
                            afterPubs += Pub(ts, published.direction, published.reason, shock)
                            bubbleCloseTs = ts + 15_000L
                        }
                    }
                }
            }
        }
        if (started) {
            closeWindowIfDue(Long.MAX_VALUE, opportunity, windowObs, engine, afterPubs, { after90Wait++ }, { after90Signal++ })
        }

        val rapidBefore = rapid(beforePubs)
        val rapidAfter = rapid(afterPubs)
        val beforeFail = beforePubs.count { it.reason == "FAIL_BRK" }
        val afterFail = afterPubs.count { it.reason == "FAIL_BRK" }
        val shockAtBefore = beforePubs.count { pub ->
            val snap = nearest(snaps, pub.ts)
            snap != null && snap.wave.shock && snap.wave.impulseSign == signOf(pub.dir)
        }
        val afterShock = afterPubs.count { it.shock }
        val beforeUp = beforePubs.count { it.dir == "UP" }
        val beforeDown = beforePubs.count { it.dir == "DOWN" }
        val afterUp = afterPubs.count { it.dir == "UP" }
        val afterDown = afterPubs.count { it.dir == "DOWN" }

        return buildString {
            appendLine("SESSION ${file.name}")
            appendLine("BEFORE pubs=${beforePubs.size} UP=$beforeUp DOWN=$beforeDown FAIL_BRK=$beforeFail shock_entry=$shockAtBefore rapid_opposite=${rapidBefore.size} obs_WAIT=$beforeWaitObs obs_UP=$beforeUpObs obs_DOWN=$beforeDownObs boundary_WAIT=$before90Wait boundary_SIGNAL=$before90Signal")
            appendLine("AFTER  pubs=${afterPubs.size} UP=$afterUp DOWN=$afterDown FAIL_BRK=$afterFail shock_entry=$afterShock rapid_opposite=${rapidAfter.size} obs_WAIT=$afterWaitObs obs_UP=$afterUpObs obs_DOWN=$afterDownObs boundary_WAIT=$after90Wait boundary_SIGNAL=$after90Signal")
            appendLine("structural_holds PULLBACK=$structuralPullback SHOCK_UNRESOLVED=$structuralShock STRUCTURE_UNSTABLE=$structuralUnstable")
            appendLine("--after-pubs--")
            if (afterPubs.isEmpty()) appendLine("(none)")
            for (pub in afterPubs) {
                val snap = nearest(snaps, pub.ts)
                val w = snap?.wave
                appendLine(
                    "AFTER ${pub.dir}/${pub.reason} shockFlag=${pub.shock} wave=${w?.sideName()} phase=${w?.phase} " +
                        "retrace=${fmt(w?.retrace ?: 0.0)} broken=${w?.broken} shock=${w?.shock} " +
                        "z3=${fmt(w?.z3 ?: 0.0)} z6=${fmt(w?.z6 ?: 0.0)} z10=${fmt(w?.z10 ?: 0.0)} z20=${fmt(w?.z20 ?: 0.0)} " +
                        "leg=${fmt(snap?.leg ?: 0.0)} vel=${fmt(snap?.vel ?: 0.0)} acc=${fmt(snap?.acc ?: 0.0)}"
                )
            }
            appendLine("--rapid-before--")
            if (rapidBefore.isEmpty()) appendLine("(none)")
            for ((a, b) in rapidBefore) {
                val snap = nearest(snaps, b.ts)
                val dt = (b.ts - a.ts) / 1000.0
                if (snap == null) {
                    appendLine("dt=${fmt(dt)} ${a.dir}->${b.dir} BEFORE=${b.reason} AFTER=NO_SNAP")
                    continue
                }
                val cls = classify(a.dir, b.dir, snap.wave)
                val w = snap.wave
                appendLine(
                    "dt=${fmt(dt)}s ${a.dir}->${b.dir} BEFORE=${b.reason} AFTER=${snap.evalDir}/${snap.evalReason} " +
                        "class=$cls wave=${w.sideName()} phase=${w.phase} retrace=${fmt(w.retrace)} broken=${w.broken} shock=${w.shock} " +
                        "z3=${fmt(w.z3)} z6=${fmt(w.z6)} z10=${fmt(w.z10)} z20=${fmt(w.z20)} leg=${fmt(snap.leg)} vel=${fmt(snap.vel)} acc=${fmt(snap.acc)} " +
                        "analyzer=${snap.analyzerDir}/${snap.analyzerReason} reg=${snap.reg} ref=${fmt(snap.ref)}"
                )
                appendLine("  prev20s ${path20(snaps, b.ts)}")
            }
            appendLine("--rapid-after--")
            if (rapidAfter.isEmpty()) appendLine("(none)")
            for ((a, b) in rapidAfter) {
                val snap = nearest(snaps, b.ts)
                val w = snap?.wave
                appendLine(
                    "dt=${fmt((b.ts - a.ts) / 1000.0)}s ${a.dir}->${b.dir} reason=${b.reason} shock=${b.shock} " +
                        "wave=${w?.sideName()} phase=${w?.phase} broken=${w?.broken} z3=${fmt(w?.z3 ?: 0.0)} z20=${fmt(w?.z20 ?: 0.0)}"
                )
            }
        }
    }

    private fun closeWindowIfDue(
        ts: Long,
        opportunity: OpportunityCycle,
        windowObs: ArrayList<Obs>,
        engine: ChangeEngine,
        afterPubs: ArrayList<Pub>,
        onWait: () -> Unit,
        onSignal: () -> Unit
    ) {
        if (ts < opportunity.resetAtMs) return
        val late = ts - opportunity.resetAtMs
        if (late < 90_000L && windowObs.isNotEmpty()) {
            val already = opportunity.publishedThisWindow
            if (!already) {
                val tSec = minOf(ts, opportunity.resetAtMs) / 1000.0
                val decision = CycleDecider.decide(windowObs.toList(), 90_000L, engine, tSec, quiet(), 0L)
                if (decision.direction == "UP" || decision.direction == "DOWN") {
                    val shock = decision.diagnostics["wave_shock"] == true
                    afterPubs += Pub(opportunity.resetAtMs, decision.direction, decision.reason, shock)
                    onSignal()
                } else onWait()
            }
        }
        val boundary = opportunity.resetAtMs
        if (late >= 90_000L) {
            val skipped = late / 90_000L
            opportunity.start(boundary + skipped * 90_000L)
        } else {
            opportunity.onMaximumWindow(boundary)
        }
        windowObs.clear()
        SignalAnalyzer.reset()
    }

    private fun rapid(pubs: List<Pub>): List<Pair<Pub, Pub>> {
        val out = ArrayList<Pair<Pub, Pub>>()
        for (i in 1 until pubs.size) {
            val a = pubs[i - 1]
            val b = pubs[i]
            if (a.dir != b.dir && a.dir in setOf("UP", "DOWN") && b.dir in setOf("UP", "DOWN")) {
                if (b.ts - a.ts <= 30_000L) out += a to b
            }
        }
        return out
    }

    private fun nearest(snaps: List<Snap>, ts: Long): Snap? {
        return snaps.filter { it.ts <= ts && ts - it.ts <= 3000L }.maxByOrNull { it.ts }
            ?: snaps.minByOrNull { abs(it.ts - ts) }?.takeIf { abs(it.ts - ts) <= 3000L }
    }

    private fun path20(snaps: List<Snap>, ts: Long): String {
        val from = ts - 20_000L
        val rows = snaps.filter { it.ts in from..ts }
        if (rows.isEmpty()) return "-"
        return rows.joinToString(" ") { s ->
            val mark = if (s.wave.shock) "!" else if (s.wave.broken) "*" else ""
            "${s.analyzerDir.firstOrNull() ?: '-'}${s.wave.sideName().firstOrNull() ?: '-'}$mark"
        }
    }

    /** A true break, B pullback, C shock chase, D noisy, E new wave already established. */
    private fun classify(prevDir: String, newDir: String, wave: WaveRead): String {
        if (!wave.valid) return "D"
        val newSign = signOf(newDir)
        val prevSign = signOf(prevDir)
        if (wave.shock && wave.impulseSign == newSign) return "C"
        if (wave.broken && wave.dominant == newSign) return "A"
        if (wave.dominant == newSign && (wave.phase == "CONTINUING" || wave.phase == "REVERSAL")) return "E"
        if (wave.dominant == prevSign && !wave.broken) return "B"
        if (wave.phase == "UNSTABLE" || wave.dominant == 0) return "D"
        return "D"
    }

    private fun signOf(dir: String): Int = when (dir) {
        "UP" -> 1
        "DOWN" -> -1
        else -> 0
    }

    private fun fmt(v: Double): String = "%.2f".format(v)

    private fun quiet() = object : ExperienceSource {
        override fun advise(action: String, state: String, regime: String, band: String) =
            ExperienceStore.Advice("NONE", 0.0, 0.0, 0.5, 0.0, 1.0, -1, "", "REPLAY", 0.5)
    }

    private fun traceOf(ys: DoubleArray, real: BooleanArray, tipConnected: Boolean): TraceResult {
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

package com.example.livesignalassistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * Chronological replay of the supplied real sessions.
 * Logged publications are the structure-only baseline already shipped.
 * Outcome labels are printed beside the lock decision and are not used to choose a side.
 */
class EntryQualityReplayTest {
    @Test
    fun replaySuppliedRealSessions() {
        val files = listOf(
            File("/home/ubuntu/.cursor/projects/workspace/uploads/01_REAL_20261010_004906_FULL_TELEMETRY_d54e.txt"),
            File("/home/ubuntu/.cursor/projects/workspace/uploads/01_REAL_20261010_003826_FULL_TELEMETRY_aa75.txt"),
            File("/home/ubuntu/.cursor/projects/workspace/uploads/01_REAL_20261009_234214_FULL_TELEMETRY_2244.txt")
        )
        assumeTrue(files.all { it.isFile })
        val experience = loadExperience()
        val report = files.joinToString("\n\n") { replay(it, experience) }
        val out = File("/opt/cursor/artifacts/entry_quality_replay.txt")
        out.parentFile?.mkdirs()
        out.writeText(report)
        println(report)
        assertTrue(report.contains("AFTER entry"))
        assertTrue(report.contains("LOCK"))
        assertEquals(0, report.lines().count { it.startsWith("PUBLISHED_SPENT") })
    }

    private class Pub(val ts: Long, val dir: String, val reason: String, val retrace: Double, val broken: Boolean, val state: String)

    private class Mark(
        val ts: Long,
        val wave: WaveRead,
        val vel: Double,
        val acc: Double,
        val step: Double,
        val seriesOk: Boolean,
        val rangePosition: Double?,
        val exhaustion: Double,
        val ref: Double
    )

    private fun replay(file: File, experience: ExperienceSource): String {
        val engine = ChangeEngine()
        val structure = OpportunityCycle()
        val entry = OpportunityCycle()
        val full = OpportunityCycle()
        val window = ArrayList<Obs>()
        val marks = ArrayList<Mark>()
        val before = ArrayList<Pub>()
        val afterStructure = ArrayList<Pub>()
        val afterEntry = ArrayList<Pub>()
        val afterFull = ArrayList<Pub>()
        val signals = ArrayList<JSONObject>()
        val locks = ArrayList<JSONObject>()
        val results = HashMap<String, String>()
        val quietSrc = quiet()
        var started = false
        var structureBubble = Long.MAX_VALUE
        var entryBubble = Long.MAX_VALUE
        var fullBubble = Long.MAX_VALUE
        var evalStructurePub = 0
        var evalEntryHold = 0
        var evalExperienceHold = 0
        var evalFullPub = 0
        SignalAnalyzer.reset()

        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val o = JSONObject(line)
                val ts = o.optLong("ts_ms")
                when (o.getString("type")) {
                    "SIGNAL_PUBLISHED" -> {
                        signals += o
                        val reason = o.optJSONObject("diagnostics")?.optString("setup_key").orEmpty().split("|").getOrNull(1).orEmpty()
                        before += Pub(ts, o.optString("direction"), reason, 0.0, false, "")
                    }
                    "TRADE_USER_OPENED" -> locks += o
                    "TRADE_RESULT" -> results[o.optString("signal_id")] = o.optString("result")
                    "BOT_OBSERVATION" -> {
                        if (!started) {
                            structure.start(ts); entry.start(ts); full.start(ts)
                            started = true
                        }
                        closeAll(ts, structure, entry, full, window, engine, afterStructure, afterEntry, afterFull, quietSrc, experience)
                        if (structure.bubbleOpen && ts >= structureBubble) structure.onBubbleTimeout()
                        if (entry.bubbleOpen && ts >= entryBubble) entry.onBubbleTimeout()
                        if (full.bubbleOpen && ts >= fullBubble) full.onBubbleTimeout()
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
                                engine.update(ts / 1000.0, DoubleArray(ys.size) { -ys[it] }, real)
                                val k = engine.kinematicsPx(ts / 1000.0)
                                if (k != null) { vel = k.first; acc = k.second }
                            }
                        }
                        val w = d?.optInt("bmp_w") ?: 1080
                        val h = d?.optInt("bmp_h") ?: 2400
                        val analyzed = SignalAnalyzer.analyze(w, h, tr, vel, acc)
                        val elapsed = (ts - entry.startMs).coerceAtLeast(0L)
                        window += Obs(elapsed, analyzed)
                        val tSec = ts / 1000.0
                        val wave = engine.wave(tSec)
                        val fe = engine.features(tSec, 1)
                        val rp = (analyzed.diagnostics["range_position"] as? Number)?.toDouble()
                        val exh = (analyzed.diagnostics["exhaustion"] as? Number)?.toDouble() ?: 0.0
                        marks += Mark(ts, wave, fe.velocity, fe.acceleration, fe.stepScale, engine.seriesStatus(tSec).reason == "OK", rp, exh, (engine.debugState()["last"] as? Number)?.toDouble() ?: Double.NaN)
                        val sEval = OpportunityDecider.evaluate(analyzed, engine, tSec, quietSrc, false)
                        val eEval = OpportunityDecider.evaluate(analyzed, engine, tSec, quietSrc, true)
                        val fEval = OpportunityDecider.evaluate(analyzed, engine, tSec, experience, true)
                        if (sEval.direction == "UP" || sEval.direction == "DOWN") evalStructurePub++
                        if ((eEval.direction != "UP" && eEval.direction != "DOWN") && (sEval.direction == "UP" || sEval.direction == "DOWN")) evalEntryHold++
                        if ((fEval.direction != "UP" && fEval.direction != "DOWN") && (eEval.direction == "UP" || eEval.direction == "DOWN")) evalExperienceHold++
                        if (fEval.direction == "UP" || fEval.direction == "DOWN") evalFullPub++
                        val sPub = structure.consider(analyzed, engine, tSec, quietSrc, false)
                        if (sPub != null) {
                            afterStructure += pubOf(ts, sPub)
                            structureBubble = ts + 15_000L
                        }
                        val ePub = entry.consider(analyzed, engine, tSec, quietSrc, true)
                        if (ePub != null) {
                            afterEntry += pubOf(ts, ePub)
                            entryBubble = ts + 15_000L
                        }
                        val fPub = full.consider(analyzed, engine, tSec, experience, true)
                        if (fPub != null) {
                            afterFull += pubOf(ts, fPub)
                            fullBubble = ts + 15_000L
                        }
                    }
                }
            }
        }
        if (started) closeAll(Long.MAX_VALUE, structure, entry, full, window, engine, afterStructure, afterEntry, afterFull, quietSrc, experience)

        return buildString {
            appendLine("SESSION ${file.name}")
            appendLine("BEFORE logged_pubs=${before.size} UP=${before.count { it.dir == "UP" }} DOWN=${before.count { it.dir == "DOWN" }}")
            appendLine("AFTER structure_only pubs=${afterStructure.size} UP=${afterStructure.count { it.dir == "UP" }} DOWN=${afterStructure.count { it.dir == "DOWN" }}")
            appendLine("AFTER entry pubs=${afterEntry.size} UP=${afterEntry.count { it.dir == "UP" }} DOWN=${afterEntry.count { it.dir == "DOWN" }}")
            appendLine("AFTER entry_plus_experience pubs=${afterFull.size} UP=${afterFull.count { it.dir == "UP" }} DOWN=${afterFull.count { it.dir == "DOWN" }}")
            appendLine("eval_frames structure_would_publish=$evalStructurePub entry_layer_held=$evalEntryHold experience_layer_held=$evalExperienceHold full_would_publish=$evalFullPub")
            appendLine("delays ${delayLine(locks)}")
            var invalidated = 0
            var kept = 0
            val keptRet = ArrayList<Double>()
            val keptAcc = ArrayList<Double>()
            val keptRp = ArrayList<Double>()
            for (lk in locks) {
                val sid = lk.optString("signal_id")
                val sg = signals.find { it.optString("signal_id") == sid }
                val delay = lk.optLong("signal_age_ms")
                val lockMark = nearest(marks, lk.optLong("ts_ms"))
                val pubMark = sg?.let { nearest(marks, it.optLong("ts_ms")) }
                val sign = if (sg?.optString("direction") == "DOWN") -1 else 1
                val logged = sg?.optJSONObject("diagnostics")
                fun loggedNum(key: String): Double? {
                    if (logged == null || !logged.has(key) || logged.isNull(key)) return null
                    val v = logged.optDouble(key)
                    return if (v.isNaN()) null else v
                }
                val lockBlock = if (lockMark == null) "NO_MARK" else EntryPermission.atEntry(
                    EntryPermission.Read(sign, lockMark.wave, lockMark.seriesOk, lockMark.vel, lockMark.acc, lockMark.step, lockMark.rangePosition, lockMark.exhaustion)
                )
                val pubBlock = if (pubMark == null) "NO_MARK" else EntryPermission.publication(
                    sign, pubMark.wave, pubMark.vel, pubMark.acc, pubMark.step,
                    loggedNum("range_position"), loggedNum("exhaustion") ?: 0.0
                )
                if (lockBlock.isNotEmpty() || pubBlock.isNotEmpty()) invalidated++ else {
                    kept++
                    if (lockMark != null) {
                        keptRet += lockMark.wave.retrace
                        keptAcc += sign * lockMark.acc
                        lockMark.rangePosition?.let { keptRp += it }
                    }
                }
                val dRef = if (pubMark != null && lockMark != null) lockMark.ref - pubMark.ref else Double.NaN
                appendLine(
                    "LOCK ${results[sid] ?: "UNLABELED"} ${sg?.optString("direction")} delay=$delay " +
                        "pub_entry=${pubBlock.ifEmpty { "OPEN" }} pub_ret=${fmt(pubMark?.wave?.retrace)} " +
                        "pub_vel=${fmt(pubMark?.vel)} pub_acc=${fmt(pubMark?.acc)} pub_step=${fmt(pubMark?.step)} " +
                        "pub_rp=${fmt(loggedNum("range_position"))} pub_exh=${fmt(loggedNum("exhaustion"))} " +
                        "lock_entry=${lockBlock.ifEmpty { "OPEN" }} " +
                        "lock_wave=${lockMark?.wave?.sideName()}/${lockMark?.wave?.phase} ret=${fmt(lockMark?.wave?.retrace)} " +
                        "z3=${fmt(lockMark?.wave?.z3)} z6=${fmt(lockMark?.wave?.z6)} z10=${fmt(lockMark?.wave?.z10)} z20=${fmt(lockMark?.wave?.z20)} " +
                        "vel=${fmt(lockMark?.vel)} acc=${fmt(lockMark?.acc)} shock=${lockMark?.wave?.shock} " +
                        "rp=${fmt(lockMark?.rangePosition)} exh=${fmt(lockMark?.exhaustion)} dRef=${fmt(dRef)}"
                )
            }
            appendLine("historical_locks=${locks.size} entry_or_lock_invalidated=$invalidated still_open=$kept")
            appendLine("kept_lock_mean_retrace=${fmt(mean(keptRet))} kept_lock_mean_signed_acc=${fmt(mean(keptAcc))} kept_lock_mean_range=${fmt(mean(keptRp))}")
            appendLine("rapid_before=${rapid(before)} rapid_after_entry=${rapid(afterEntry)} fail_brk_after=${afterEntry.count { it.reason == "FAIL_BRK" }}")
            for (pub in afterEntry) {
                if (!pub.broken && pub.retrace >= 0.5) appendLine("PUBLISHED_SPENT ${pub.dir} ret=${pub.retrace}")
                assertTrue(pub.state.startsWith("VALID") || pub.state.isEmpty())
            }
        }
    }

    private fun pubOf(ts: Long, r: SignalResult): Pub {
        val retrace = (r.diagnostics["wave_retrace"] as? Number)?.toDouble() ?: 0.0
        val broken = r.diagnostics["wave_broken"] == true
        val state = r.diagnostics["entry_state"] as? String ?: ""
        return Pub(ts, r.direction, r.reason, retrace, broken, state)
    }

    private fun closeAll(
        ts: Long,
        structure: OpportunityCycle,
        entry: OpportunityCycle,
        full: OpportunityCycle,
        window: ArrayList<Obs>,
        engine: ChangeEngine,
        afterStructure: ArrayList<Pub>,
        afterEntry: ArrayList<Pub>,
        afterFull: ArrayList<Pub>,
        quietSrc: ExperienceSource,
        experience: ExperienceSource
    ) {
        val boundary = structure.resetAtMs
        if (ts < boundary) return
        val late = ts - boundary
        if (late < 90_000L && window.isNotEmpty()) {
            val tSec = kotlin.math.min(ts, boundary) / 1000.0
            fun take(cycle: OpportunityCycle, gate: Boolean, exp: ExperienceSource, pubs: ArrayList<Pub>) {
                if (cycle.publishedThisWindow) return
                val decision = CycleDecider.decide(window.toList(), 90_000L, engine, tSec, exp, 0L, gate)
                if (decision.direction == "UP" || decision.direction == "DOWN") pubs += pubOf(boundary, decision)
            }
            take(structure, false, quietSrc, afterStructure)
            take(entry, true, quietSrc, afterEntry)
            take(full, true, experience, afterFull)
        }
        if (late >= 90_000L) {
            val skipped = late / 90_000L
            val next = boundary + skipped * 90_000L
            structure.start(next); entry.start(next); full.start(next)
        } else {
            structure.onMaximumWindow(boundary)
            entry.onMaximumWindow(boundary)
            full.onMaximumWindow(boundary)
        }
        window.clear()
        SignalAnalyzer.reset()
    }

    private fun loadExperience(): ExperienceSource {
        val file = File("/home/ubuntu/.cursor/projects/workspace/uploads/02_EXPERIENCE_84_RESULTS_62df.txt")
        if (!file.isFile) return quiet()
        val cells = JSONObject(file.readText()).optJSONArray("cells") ?: return quiet()
        val map = HashMap<String, DoubleArray>()
        for (i in 0 until cells.length()) {
            val c = cells.getJSONObject(i)
            map[c.getString("k")] = doubleArrayOf(c.getDouble("w"), c.getDouble("l"))
        }
        return object : ExperienceSource {
            override fun advise(action: String, state: String, regime: String, band: String) =
                ExperienceMath.advise(map, action, state, regime, band)
        }
    }

    private fun quiet() = object : ExperienceSource {
        override fun advise(action: String, state: String, regime: String, band: String) =
            ExperienceStore.Advice("NONE", 0.0, 0.0, 0.5, 0.0, 1.0, -1, "", "REPLAY", 0.5)
    }

    private fun nearest(marks: List<Mark>, ts: Long): Mark? =
        marks.filter { it.ts <= ts && ts - it.ts <= 3000L }.maxByOrNull { it.ts }

    private fun rapid(pubs: List<Pub>): Int {
        var n = 0
        for (i in 1 until pubs.size) {
            val a = pubs[i - 1]
            val b = pubs[i]
            if (a.dir != b.dir && b.ts - a.ts <= 30_000L) n++
        }
        return n
    }

    private fun delayLine(locks: List<JSONObject>): String {
        val delays = locks.map { it.optLong("signal_age_ms") }.filter { it > 0 }.sorted()
        if (delays.isEmpty()) return "n=0"
        val mean = delays.average()
        val median = delays[delays.size / 2]
        return "n=${delays.size} mean=${mean.toLong()} median=$median min=${delays.first()} max=${delays.last()}"
    }

    private fun mean(xs: List<Double>): Double = if (xs.isEmpty()) Double.NaN else xs.average()
    private fun fmt(v: Double?): String = if (v == null || v.isNaN()) "-" else "%.2f".format(v)

    private fun traceOf(ys: DoubleArray, real: BooleanArray, tipConnected: Boolean): TraceResult {
        var hi = -1
        var nReal = 0
        for (i in ys.indices) if (i < real.size && real[i]) { hi = i; nReal++ }
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
        } finally { inf.end() }
        val raw = rawOut.toByteArray()
        if (raw.size < 2) return null
        val n = ((raw[0].toInt() and 255) shl 8) or (raw[1].toInt() and 255)
        if (raw.size < 2 + 2 * n) return null
        val ys = DoubleArray(n) { i -> (((raw[2 + 2 * i].toInt() and 255) shl 8) or (raw[3 + 2 * i].toInt() and 255)).toDouble() }
        val base = 2 + 2 * n
        val real = BooleanArray(n) { i -> base + i / 8 < raw.size && (raw[base + i / 8].toInt() shr (i % 8) and 1) == 1 }
        return Pair(ys, real)
    }
}

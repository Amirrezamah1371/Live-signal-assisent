package com.example.livesignalassistant

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.Inflater

/**
 * Baseline reconstruction of executed trades at publication and at the user lock.
 * Outcome labels are printed. They are not used to choose a side.
 */
class ForensicLockReplayTest {
    @Test
    fun reconstructLocksOnNewestRealSessions() {
        val files = listOf(
            File("/home/ubuntu/.cursor/projects/workspace/uploads/01_REAL_20261010_004906_FULL_TELEMETRY_d54e.txt"),
            File("/home/ubuntu/.cursor/projects/workspace/uploads/01_REAL_20261010_003826_FULL_TELEMETRY_aa75.txt"),
            File("/home/ubuntu/.cursor/projects/workspace/uploads/01_REAL_20261009_234214_FULL_TELEMETRY_2244.txt")
        )
        assumeTrue(files.all { it.isFile })
        val report = files.joinToString("\n\n") { replay(it) }
        val out = File("/opt/cursor/artifacts/forensic_lock_baseline.txt")
        out.parentFile?.mkdirs()
        out.writeText(report)
        println(report)
        assertTrue(report.contains("LOCK"))
    }

    private class Mark(val ts: Long, val ref: Double, val wave: WaveRead, val vel: Double, val acc: Double, val leg: Double)

    private fun replay(file: File): String {
        val engine = ChangeEngine()
        val marks = HashMap<Long, Mark>()
        val signals = ArrayList<JSONObject>()
        val locks = ArrayList<JSONObject>()
        val results = HashMap<String, String>()
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val o = JSONObject(line)
                when (o.getString("type")) {
                    "BOT_OBSERVATION" -> {
                        val ts = o.optLong("ts_ms")
                        val d = o.optJSONObject("diagnostics")
                        val decoded = d?.optString("trace_b64")?.let { decodeTrace(it) }
                        if (decoded != null) {
                            val (ys, real) = decoded
                            val connected = TraceGeometry.adoptConnectedTip(ys, real)
                            if (connected && realFrac(ys, real) >= 0.30 && ys.size >= 72) {
                                val path = DoubleArray(ys.size) { -ys[it] }
                                engine.update(ts / 1000.0, path, real)
                            }
                        }
                        val tSec = ts / 1000.0
                        val wave = engine.wave(tSec)
                        val fe = engine.features(tSec, 1)
                        val ref = (engine.debugState()["last"] as? Number)?.toDouble() ?: Double.NaN
                        marks[ts] = Mark(ts, ref, wave, fe.velocity, fe.acceleration, engine.localMotion().leg)
                    }
                    "SIGNAL_PUBLISHED" -> signals += o
                    "TRADE_USER_OPENED" -> locks += o
                    "TRADE_RESULT" -> results[o.optString("signal_id")] = o.optString("result")
                }
            }
        }
        return buildString {
            appendLine("SESSION ${file.name}")
            val delays = ArrayList<Long>()
            for (lk in locks) {
                val sid = lk.optString("signal_id")
                val sg = signals.find { it.optString("signal_id") == sid } ?: continue
                val delay = lk.optLong("signal_age_ms")
                delays += delay
                val pub = nearest(marks, sg.optLong("ts_ms"))
                val lock = nearest(marks, lk.optLong("ts_ms"))
                val d = sg.optJSONObject("diagnostics")
                val move = if (pub != null && lock != null) lock.ref - pub.ref else Double.NaN
                appendLine(
                    "${results[sid]} ${sg.optString("direction")} score=${sg.optInt("model_score")} " +
                        "setup=${d?.optString("setup_key")} delay=${delay} " +
                        "pubWave=${pub?.wave?.sideName()}/${pub?.wave?.phase} ret=${fmt(pub?.wave?.retrace)} " +
                        "z3=${fmt(pub?.wave?.z3)} z6=${fmt(pub?.wave?.z6)} z10=${fmt(pub?.wave?.z10)} z20=${fmt(pub?.wave?.z20)} " +
                        "vel=${fmt(pub?.vel)} acc=${fmt(pub?.acc)} leg=${fmt(pub?.leg)} shock=${pub?.wave?.shock} " +
                        "rp=${fmt(d?.optDouble("range_position"))} exh=${fmt(d?.optDouble("exhaustion"))} " +
                        "LOCK z3=${fmt(lock?.wave?.z3)} z6=${fmt(lock?.wave?.z6)} z10=${fmt(lock?.wave?.z10)} z20=${fmt(lock?.wave?.z20)} " +
                        "wave=${lock?.wave?.sideName()}/${lock?.wave?.phase} ret=${fmt(lock?.wave?.retrace)} " +
                        "vel=${fmt(lock?.vel)} acc=${fmt(lock?.acc)} leg=${fmt(lock?.leg)} shock=${lock?.wave?.shock} " +
                        "dRef=${fmt(move)}"
                )
            }
            if (delays.isNotEmpty()) {
                val sorted = delays.sorted()
                val mean = delays.average()
                val median = sorted[sorted.size / 2]
                appendLine("delay_ms n=${delays.size} mean=${mean.toLong()} median=$median min=${sorted.first()} max=${sorted.last()}")
            }
        }
    }

    private fun nearest(marks: Map<Long, Mark>, ts: Long): Mark? =
        marks.values.filter { it.ts <= ts && ts - it.ts <= 3000L }.maxByOrNull { it.ts }

    private fun fmt(v: Double?): String = if (v == null || v.isNaN()) "-" else "%.2f".format(v)
    private fun realFrac(ys: DoubleArray, real: BooleanArray): Double {
        if (ys.isEmpty()) return 0.0
        return real.count { it }.toDouble() / ys.size
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

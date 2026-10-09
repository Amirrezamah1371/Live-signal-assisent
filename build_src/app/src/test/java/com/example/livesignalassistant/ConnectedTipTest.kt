package com.example.livesignalassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * The adopted tip has to be the connected price path.
 * These cases are shapes: a long right-edge island, a steep sampled move, and two
 * disconnected pieces of similar size. None of them encodes a logged trade.
 */
class ConnectedTipTest {
    private fun lastReal(real: BooleanArray): Int = real.indexOfLast { it }

    @Test
    fun longDetachedFragmentAfterALargeGapIsRejected() {
        val n = 360
        val path = DoubleArray(n) { i -> 80.0 + 0.35 * i }
        val real = BooleanArray(n)
        for (i in 20..280) real[i] = true
        // Twenty-two columns, past a gap the short-speck rule does not remove.
        for (i in 326..347) {
            real[i] = true
            path[i] = 40.0
        }
        assertTrue(TraceGeometry.adoptConnectedTip(path, real))
        assertFalse(real[326])
        assertFalse(real[347])
        assertEquals(280, lastReal(real))
        val motion = TraceGeometry.measure(path, real)
        assertTrue(motion.usable)
        assertTrue("island became the tip, net=${motion.net}", motion.net < 250.0)
    }

    @Test
    fun detachedFragmentLongerThanEightColumnsIsRejected() {
        val n = 300
        val path = DoubleArray(n) { 200.0 - 0.2 * it }
        val real = BooleanArray(n)
        for (i in 10..200) real[i] = true
        val fragment = 240..259
        for (i in fragment) {
            real[i] = true
            path[i] = 900.0
        }
        assertTrue(fragment.count() > 8)
        assertTrue(TraceGeometry.adoptConnectedTip(path, real))
        for (i in fragment) assertFalse("column $i kept", real[i])
        assertEquals(200, lastReal(real))
    }

    @Test
    fun connectedRisingTraceIsPreserved() {
        val n = 180
        val path = DoubleArray(n) { 15.0 + 0.8 * it }
        val real = BooleanArray(n) { true }
        assertTrue(TraceGeometry.adoptConnectedTip(path, real))
        assertTrue(real.all { it })
        val motion = TraceGeometry.measure(path, real)
        assertTrue(motion.usable)
        assertTrue(motion.net > 0.0)
        assertTrue(motion.leg >= 0.0)
    }

    @Test
    fun connectedFallingTraceIsPreserved() {
        val n = 180
        val path = DoubleArray(n) { 400.0 - 0.8 * it }
        val real = BooleanArray(n) { true }
        assertTrue(TraceGeometry.adoptConnectedTip(path, real))
        assertTrue(real.all { it })
        val motion = TraceGeometry.measure(path, real)
        assertTrue(motion.usable)
        assertTrue(motion.net < 0.0)
        assertTrue(motion.leg <= 0.0)
    }

    @Test
    fun steepMoveWithAShortHoleIsNotStripped() {
        val n = 200
        val path = DoubleArray(n)
        val real = BooleanArray(n) { true }
        for (i in 0 until n) path[i] = 30.0 + 0.4 * i
        // Three missed columns, then a steep continuation. The hole is short; the move is real.
        for (i in 140..142) real[i] = false
        for (i in 143 until n) path[i] = path[139] + 90.0 + 1.6 * (i - 143)
        assertTrue(TraceGeometry.adoptConnectedTip(path, real))
        assertTrue(real[143])
        assertTrue(real[n - 1])
        assertEquals(n - 1, lastReal(real))
        val motion = TraceGeometry.measure(path, real)
        assertTrue(motion.usable)
        assertTrue("steep continuation was deleted, net=${motion.net}", motion.net > 50.0)
    }

    @Test
    fun shortRunHiddenBehindALongerFragmentIsNotTheTip() {
        val n = 397
        val path = DoubleArray(n)
        val real = BooleanArray(n)
        for (i in 40..305) {
            real[i] = true
            path[i] = 1000.0 + ((i - 40) % 3) * 6.0
        }
        val bodyTip = path[305]
        // Five identical columns, past a gap wide enough for the speck rule, close enough
        // vertically that the reconnect budget would keep them if they were judged alone.
        for (i in 335..339) {
            real[i] = true
            path[i] = bodyTip - 33.0
        }
        // A longer fragment farther right hides that stub from the first speck pass.
        for (i in 380..393) {
            real[i] = true
            path[i] = bodyTip + 700.0
        }
        val once = real.copyOf()
        assertTrue(TraceGeometry.adoptConnectedTip(path, once))
        assertEquals(305, lastReal(once))
        assertFalse(once[335])
        assertFalse(once[380])
        val twice = once.copyOf()
        assertTrue(TraceGeometry.adoptConnectedTip(path, twice))
        assertTrue(twice.contentEquals(once))
        val ce = ChangeEngine()
        ce.update(0.0, path, real.copyOf())
        val info = ce.update(1.0, path, real.copyOf())
        assertEquals("OK", info["reg_status"])
        val ref = (info["ref_v"] as Number).toDouble()
        val tail = doubleArrayOf(path[303], path[304], path[305]).sorted()[1]
        assertEquals(tail, ref, 1e-6)
    }

    @Test
    fun ambiguousDisconnectedGeometryFailsClosed() {
        val n = 240
        val path = DoubleArray(n)
        val real = BooleanArray(n)
        for (i in 0 until 100) {
            real[i] = true
            path[i] = 20.0 + 1.5 * i
        }
        for (i in 150 until 250.coerceAtMost(n)) {
            real[i] = true
            path[i] = 520.0 + 1.5 * (i - 150)
        }
        val before = real.copyOf()
        assertFalse(TraceGeometry.adoptConnectedTip(path, real))
        assertTrue(real.contentEquals(before))
        assertFalse(TraceGeometry.measure(path, before).usable)
    }

    @Test
    fun rejectedFragmentCannotBeAnAcceptedRegistration() {
        val ce = ChangeEngine()
        val (seed, seedReal) = body(0, 0.0, withIsland = false)
        ce.update(0.0, seed, seedReal)
        val (frame, real) = body(2, 12.0, withIsland = false)
        val ok = ce.update(1.0, frame, real)
        assertEquals("OK", ok["reg_status"])
        val trusted = ok["ref_v"] as Double

        val (bad, badReal) = ambiguousPair()
        val rejected = ce.update(2.0, bad, badReal)
        assertEquals("FAIL", rejected["reg_status"])
        assertEquals("TIP_DISCONNECTED", rejected["reg_reason"])
        assertNotEquals("ACCEPTED", rejected["reg_reason"])
        assertEquals(trusted, rejected["ref_v"] as Double, 1e-6)
        assertFalse(ce.registrationTrusted(2.0) && rejected["reg_status"] == "OK")
    }

    @Test
    fun movingBodyDoesNotFreezeReferenceOnADetachedIsland() {
        val ce = ChangeEngine()
        val refs = ArrayList<Double>()
        for (t in 0..12) {
            val (path, real) = body(t, tip = 8.0 * t, withIsland = true)
            val info = ce.update(t.toDouble(), path, real)
            if (info["reg_status"] == "OK" || t == 0) refs.add(info["ref_v"] as Double)
            assertNotEquals("ACCEPTED on a disconnected tip", "TIP_DISCONNECTED", info["reg_reason"])
            val ref = info["ref_v"] as Double
            assertTrue("ref froze on the island: $ref", abs(ref - ISLAND) > 200.0)
        }
        assertTrue("registration never followed the body: $refs", refs.size >= 4)
        assertTrue("body tip did not move in the reference: $refs", refs.last() > refs.first() + 20.0)
    }

    @Test
    fun latestSessionDoesNotAdoptTheDetachedRightEdge() {
        val configured = System.getProperty("lsa.latest.telemetry").orEmpty()
        val file = File(
            configured.ifEmpty {
                "/home/ubuntu/.cursor/projects/workspace/uploads/LATEST_SESSION_FULL_TELEMETRY_d651.txt"
            }
        )
        assumeTrue("latest session telemetry is not on this machine", file.isFile)
        val report = replaySession(file)
        File("/tmp/lsa_connected_tip_replay.txt").writeText(report)
        println(report)
        assertFalse(report.contains("HUD_TIP_ACCEPTED"))
        assertTrue(report.contains("cycles 6 and 7 detached tips accepted: 0"))
    }

    private fun body(shift: Int, tip: Double, withIsland: Boolean): Pair<DoubleArray, BooleanArray> {
        val n = 240
        val path = DoubleArray(n)
        val real = BooleanArray(n)
        for (i in 0..189) {
            real[i] = true
            path[i] = 40.0 + 0.42 * (i + shift)
            if (i >= 178) path[i] += tip
        }
        if (withIsland) {
            for (i in 214 until n) {
                real[i] = true
                path[i] = ISLAND
            }
        }
        return path to real
    }

    private fun ambiguousPair(): Pair<DoubleArray, BooleanArray> {
        val n = 240
        val path = DoubleArray(n)
        val real = BooleanArray(n)
        for (i in 0 until 100) {
            real[i] = true
            path[i] = 10.0 + 2.0 * i
        }
        for (i in 150 until n) {
            real[i] = true
            path[i] = 480.0 + 2.4 * (i - 150)
        }
        return path to real
    }

    private fun quiet() = object : ExperienceSource {
        override fun advise(action: String, state: String, regime: String, band: String) =
            ExperienceStore.Advice("NONE", 0.0, 0.0, 0.5, 0.0, 1.0, -1, "", "REPLAY", 0.5)
    }

    private fun decode(b64: String): Pair<DoubleArray, BooleanArray>? {
        if (b64.isEmpty()) return null
        val inf = Inflater()
        inf.setInput(Base64.getDecoder().decode(b64))
        val rawOut = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && inf.needsInput()) break
                rawOut.write(buf, 0, n)
            }
        } finally {
            inf.end()
        }
        val raw = rawOut.toByteArray()
        if (raw.size < 2) return null
        val n = ((raw[0].toInt() and 0xff) shl 8) or (raw[1].toInt() and 0xff)
        if (raw.size < 2 + 2 * n) return null
        val ys = DoubleArray(n)
        for (i in 0 until n) {
            val o = 2 + 2 * i
            ys[i] = (((raw[o].toInt() and 0xff) shl 8) or (raw[o + 1].toInt() and 0xff)).toDouble()
        }
        val real = BooleanArray(n)
        val mask = 2 + 2 * n
        for (i in 0 until n) {
            if (mask + i / 8 < raw.size) {
                real[i] = (raw[mask + i / 8].toInt() and (1 shl (i % 8))) != 0
            }
        }
        return ys to real
    }

    private fun tipOf(ys: DoubleArray, real: BooleanArray): Double {
        val vals = ArrayList<Double>(3)
        for (i in ys.lastIndex downTo 0) {
            if (i < real.size && real[i]) vals.add(ys[i])
            if (vals.size == 3) break
        }
        if (vals.isEmpty()) return Double.NaN
        return vals.sorted()[vals.size / 2]
    }

    private fun replaySession(file: File): String {
        val engine = ChangeEngine()
        val obs = ArrayList<Obs>()
        val lines = ArrayList<String>()
        var cycle = -1
        var adoptedDetached = 0
        var cycleDetachedAccepted = 0
        data class Snap(
            val tip: Double, val leg: Double, val reg: String, val reason: String,
            val ref: Double, val analyzer: String, val side: Int
        )
        val before = HashMap<Int, Snap>()
        val after = HashMap<Int, Snap>()
        val refMin = HashMap<Int, Double>()
        val refMax = HashMap<Int, Double>()
        val okCount = HashMap<Int, Int>()
        file.bufferedReader().useLines { seq ->
            seq.forEach { line ->
                val o = JSONObject(line)
                when (o.optString("type")) {
                    "BOT_OBSERVATION" -> {
                        val cyc = o.optInt("cycle")
                        if (cyc != cycle) {
                            cycle = cyc
                            obs.clear()
                            SignalAnalyzer.reset()
                            cycleDetachedAccepted = 0
                        }
                        val d = o.optJSONObject("diagnostics")
                        val decoded = d?.optString("trace_b64")?.let { decode(it) }
                        val rawTip = if (decoded == null) Double.NaN else tipOf(decoded.first, decoded.second)
                        var reg = "SKIP"
                        var reason = ""
                        var ref = Double.NaN
                        var leg = Double.NaN
                        var analyzer = "NO_TRACE"
                        var side = 0
                        if (decoded != null) {
                            val (ys, real) = decoded
                            val rawReal = real.copyOf()
                            val connected = TraceGeometry.adoptConnectedTip(ys, real)
                            val cleanedTip = tipOf(ys, real)
                            val detached = rawTip.isFinite() && cleanedTip.isFinite() && abs(rawTip - cleanedTip) > 80.0
                            val path = DoubleArray(ys.size) { -ys[it] }
                            val motion = TraceGeometry.measure(path, real)
                            leg = if (motion.usable) motion.leg else Double.NaN
                            if (connected && real.count { it } >= 72) {
                                val info = engine.update(o.optLong("ts_ms") / 1000.0, path, real)
                                reg = info["reg_status"] as String
                                reason = info["reg_reason"] as String
                                ref = info["ref_v"] as Double
                                if (detached && reg == "OK" && abs(ref - (-rawTip)) < 8.0) {
                                    adoptedDetached++
                                    cycleDetachedAccepted++
                                    lines.add("HUD_TIP_ACCEPTED cycle=$cyc rawTip=$rawTip ref=$ref")
                                }
                            } else {
                                reg = "FAIL"
                                reason = if (connected) "NO_CURRENT_TIP" else "TIP_DISCONNECTED"
                                ref = (engine.debugState()["last"] as Double)
                            }
                            val h = d?.optInt("bmp_h", 2340) ?: 2340
                            val w = d?.optInt("bmp_w", 1080) ?: 1080
                            val tr = traceOf(ys, real, connected)
                            val analyzed = SignalAnalyzer.analyze(w, h, tr, 0.0, 0.0)
                            analyzer = analyzed.reason
                            side = analyzed.side
                            obs.add(Obs(o.optLong("cycle_elapsed_ms"), analyzed))
                        }
                        if (cyc in 5..8) {
                            before[cyc] = Snap(
                                rawTip,
                                Double.NaN,
                                d?.optString("reg_status") ?: "",
                                d?.optString("reg_reason") ?: "",
                                d?.optDouble("ref_v") ?: Double.NaN,
                                o.optString("reason"),
                                o.optInt("side")
                            )
                        }
                        if (cyc in 5..8) {
                            after[cyc] = Snap(if (decoded == null) Double.NaN else tipOf(decoded.first, decoded.second), leg, reg, reason, ref, analyzer, side)
                            if (ref.isFinite()) {
                                refMin[cyc] = minOf(refMin[cyc] ?: ref, ref)
                                refMax[cyc] = maxOf(refMax[cyc] ?: ref, ref)
                            }
                            if (reg == "OK") okCount[cyc] = (okCount[cyc] ?: 0) + 1
                        }
                    }
                    "NINETY_SECOND_DECISION" -> {
                        val cyc = o.optInt("cycle")
                        if (cyc !in 5..8) return@forEach
                        val tNow = o.optLong("ts_ms") / 1000.0
                        val decided = CycleDecider.decide(obs.toList(), 90000L, engine, tNow, quiet(), 0L)
                        val b = before[cyc]
                        val a = after[cyc]
                        lines.add(
                            "cycle $cyc BEFORE tip=${fmt(b?.tip)} reg=${b?.reg}/${b?.reason} ref=${fmt(b?.ref)} " +
                                "analyzer=${b?.analyzer} side=${b?.side} decision=${o.optString("reason")} " +
                                "dir=${o.optString("direction")}"
                        )
                        lines.add(
                            "cycle $cyc AFTER  tip=${fmt(a?.tip)} leg=${fmt(a?.leg)} reg=${a?.reg}/${a?.reason} ref=${fmt(a?.ref)} " +
                                "ref_span=${fmt(refMin[cyc])}..${fmt(refMax[cyc])} ok=${okCount[cyc] ?: 0} " +
                                "analyzer=${a?.analyzer} side=${a?.side} decision=${decided.reason} dir=${decided.direction} " +
                                "trusted=${engine.registrationTrusted(tNow)} series=${engine.seriesStatus(tNow).reason} " +
                                "detached_accepts=$cycleDetachedAccepted"
                        )
                    }
                }
            }
        }
        lines.add("cycles 6 and 7 detached tips accepted: $adoptedDetached")
        return lines.joinToString("\n")
    }

    private fun traceOf(ys: DoubleArray, real: BooleanArray, connected: Boolean): TraceResult {
        var hi = -1
        var nReal = 0
        for (i in ys.indices) if (real[i]) {
            hi = i
            nReal++
        }
        val realFrac = if (ys.isEmpty()) 0.0 else nReal.toDouble() / ys.size
        val tipGap = if (hi < 0 || ys.isEmpty()) 1.0 else (ys.size - 1 - hi).toDouble() / ys.size
        return TraceResult(ys, real, realFrac, 0.0, 0.0, true, 0, 1, 0.8, 2, tipGap, connected)
    }

    private fun fmt(v: Double?) = if (v == null || !v.isFinite()) "na" else "%.1f".format(v)

    companion object {
        private const val ISLAND = 4000.0
    }
}

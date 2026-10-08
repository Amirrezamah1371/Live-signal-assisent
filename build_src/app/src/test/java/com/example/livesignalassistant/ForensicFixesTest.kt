package com.example.livesignalassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

class ForensicFixesTest {
    private fun base(n: Int): DoubleArray = DoubleArray(n) { i -> 100.0 + 0.15 * i + 2.0 * sin(i / 9.0) }

    private fun scrolled(shift: Int, scale: Double = 1.0, bias: Double = 0.0, tip: Double = 0.0, tipN: Int = 0): DoubleArray {
        val n = 160
        val src = base(n + shift + 5)
        val out = DoubleArray(n) { i -> scale * src[i + shift] + bias }
        if (tipN > 0) for (i in n - tipN until n) out[i] += tip
        return out
    }

    /** 72.0.1 fit: RMSE over the overlap, only 3 samples dropped at the tip. */
    private fun legacyRegister(old: DoubleArray, nw: DoubleArray): DoubleArray? {
        val n0 = old.size
        val maxShift = maxOf(3, n0 / 8)
        var best: DoubleArray? = null
        for (s in 0..maxShift) {
            val len = minOf(n0 - s, nw.size) - 3
            if (len < maxOf(30, (0.5 * n0).toInt())) continue
            var mx = 0.0; var my = 0.0
            for (i in 0 until len) { mx += old[s + i]; my += nw[i] }
            mx /= len; my /= len
            var vx = 0.0; var cov = 0.0; var vy = 0.0
            for (i in 0 until len) {
                val dx = old[s + i] - mx; val dy = nw[i] - my
                vx += dx * dx; cov += dx * dy; vy += dy * dy
            }
            vx /= len; cov /= len; vy /= len
            if (vx < 1e-9) continue
            val aRaw = cov / vx
            val a = aRaw.coerceIn(0.35, 2.8)
            val b = my - a * mx
            var sse = 0.0
            for (i in 0 until len) { val e = nw[i] - (a * old[s + i] + b); sse += e * e }
            val res = kotlin.math.sqrt(sse / len) / (kotlin.math.sqrt(vy) + 1e-9)
            val sc = res + (if (abs(aRaw - a) > 1e-9) 1.0 else 0.0)
            if (best == null || sc < best[0]) best = doubleArrayOf(sc, s.toDouble(), a, b)
        }
        return best
    }

    @Test
    fun tipMoveKeepsTheTrueShiftAndTheReferenceMove() {
        val old = scrolled(0)
        val nw = scrolled(2, scale = 1.4, bias = 10.0, tip = 80.0, tipN = 18)
        val legacy = legacyRegister(old, nw)!!
        assertTrue("legacy alignment should drift off the true shift, was ${legacy[1]}", legacy[1] >= 10.0)
        val ce = ChangeEngine()
        ce.update(0.0, old)
        val v0 = ce.debugState()["last"] as Double
        val info = ce.update(1.0, nw)
        assertEquals("OK", info["reg_status"])
        assertEquals(2.0, info["reg_best_shift"] as Double, 1e-6)
        assertEquals(1.4, info["reg_a"] as Double, 1e-6)
        val dv = (ce.debugState()["last"] as Double) - v0
        assertEquals(57.365, dv, 0.30)
    }

    @Test
    fun scatteredColumnFlickerIsAcceptedWithoutRelaxingTheScoreGate() {
        val old = base(160)
        val nw = old.copyOf()
        for (i in 0 until 140) if (i % 8 == 0) nw[i] += 15.0
        val legacy = legacyRegister(old, nw)!!
        assertTrue("legacy score ${legacy[0]}", legacy[0] >= 0.30)
        val ce = ChangeEngine()
        ce.update(0.0, old)
        val info = ce.update(1.0, nw)
        assertEquals("OK", info["reg_status"])
        assertTrue((info["reg_score"] as Double) < 0.30)
    }

    @Test
    fun unrelatedPathIsRejectedAndOneFailureDoesNotWipe() {
        val ce = ChangeEngine()
        val good = scrolled(0)
        ce.update(0.0, good)
        repeat(6) { k -> ce.update((k + 1).toDouble(), scrolled((k + 1) * 2)) }
        val before = ce.pointCount()
        val last = ce.debugState()["last"] as Double
        val bad = DoubleArray(160) { i -> if (i % 2 == 0) 1000.0 else -1000.0 }
        val held = ce.update(8.0, bad)
        assertEquals("FAIL", held["reg_status"])
        assertTrue((held["reg_reason"] as String).startsWith("HELD_"))
        assertEquals(before, ce.pointCount())
        assertEquals(last, ce.debugState()["last"] as Double, 1e-9)
        val again = ce.update(9.0, bad)
        assertEquals("FAIL", again["reg_status"])
        assertFalse((again["reg_reason"] as String).startsWith("RESET_"))
        assertEquals(before, ce.pointCount())
        assertEquals(last, ce.debugState()["last"] as Double, 1e-9)
        assertEquals(false, ce.debugState()["provisional"])
        // Two seconds after the last trusted point the tail is stale, and the points are still there.
        assertFalse(ce.features(9.0, 1).valid)
        assertEquals(before, ce.pointCount())
    }

    @Test
    fun staleTailIsNotAFlatValidSeries() {
        val ce = feedSmooth(20)
        assertTrue(ce.features(19.0, 1).valid)
        assertEquals("OK", ce.seriesStatus(19.0).reason)
        assertFalse(ce.features(29.0, 1).valid)
        assertEquals("STALE_TAIL", ce.seriesStatus(29.0).reason)
        assertFalse(ce.registrationTrusted(29.0))
    }

    @Test
    fun maskedGarbageEndsStillRegister() {
        val old = scrolled(0)
        val nw = scrolled(2, scale = 1.2, bias = 4.0)
        val real = BooleanArray(nw.size) { true }
        for (i in 0 until 6) { real[i] = false; nw[i] = 0.0 }
        for (i in nw.size - 8 until nw.size) { real[i] = false; nw[i] = 999.0 }
        val bare = ChangeEngine()
        bare.update(0.0, old)
        val bareInfo = bare.update(1.0, nw)
        assertTrue("unmasked garbage should not be a clean accept, was ${bareInfo["reg_status"]} score=${bareInfo["reg_score"]}", bareInfo["reg_status"] != "OK" || (bareInfo["reg_score"] as Double) > 0.05)
        val masked = ChangeEngine()
        masked.update(0.0, old, BooleanArray(old.size) { true })
        val info = masked.update(1.0, nw, real)
        assertEquals("OK", info["reg_status"])
        assertEquals(2.0, info["reg_best_shift"] as Double, 1e-6)
    }

    @Test
    fun reversedPathDoesNotRegister() {
        val old = base(160)
        val ce = ChangeEngine()
        ce.update(0.0, old)
        val info = ce.update(1.0, DoubleArray(old.size) { old[old.lastIndex - it] })
        assertEquals("FAIL", info["reg_status"])
    }

    @Test
    fun noRecentEvidenceCannotPublish() {
        val obs = (0 until 50).map { i ->
            Obs(i * 1000L, SignalResult(
                "UP", 60, 80, entryQuality = 80, conflict = 10, reason = "TREND",
                diagnostics = mapOf("exhaustion" to 0.0, "trend_regime" to 0.8),
                side = 1, traceQuality = 0.95
            ))
        }
        val result = CycleDecider.decide(obs, 90000L, ChangeEngine(), 90.0, none(), 0L)
        assertEquals("WAIT", result.direction)
        assertEquals("NO_RECENT_EVIDENCE", result.reason)
        assertEquals(0, result.signalQuality)
        assertEquals(false, result.diagnostics["strength_is_probability"])
    }

    @Test
    fun lateRefusalsCannotFlipTheDirection() {
        val ce = feedSmooth(20)
        val obs = ArrayList<Obs>()
        repeat(20) { i ->
            obs += Obs(40000L + i * 1000L, SignalResult(
                "UP", 60, 80, entryQuality = 80, conflict = 8, reason = "TREND",
                diagnostics = mapOf("exhaustion" to 0.0, "trend_regime" to 0.85),
                side = 1, traceQuality = 0.95
            ))
        }
        repeat(40) { i ->
            obs += Obs(60000L + i * 500L, SignalResult(
                "WAIT", 60, 70, entryQuality = 60, conflict = 20, reason = "LATE_ENTRY_RISK",
                diagnostics = mapOf("exhaustion" to 0.7, "trend_regime" to 0.8),
                side = -1, traceQuality = 0.9
            ))
        }
        val result = CycleDecider.decide(obs, 90000L, ce, 19.0, none(), 0L)
        assertNotEquals("DOWN", result.direction)
        assertEquals("ENTRY_REFUSED", result.reason)
    }

    @Test
    fun trustedRecentContinuationCanPublishUp() {
        val ce = feedSmooth(20)
        val obs = (0 until 50).map { i ->
            Obs(40000L + i * 1000L, SignalResult(
                "UP", 60, 80, entryQuality = 78, conflict = 12, reason = "TREND",
                diagnostics = mapOf("exhaustion" to 0.05, "trend_regime" to 0.8),
                side = 1, traceQuality = 0.92
            ))
        }
        val result = CycleDecider.decide(obs, 90000L, ce, 19.0, none(), 0L)
        assertEquals(result.reason, "UP", result.direction)
        assertTrue(result.signalQuality > 0)
        assertEquals(true, result.diagnostics["recent_valid"])
        assertEquals(true, result.diagnostics["reg_trusted"])
        assertEquals(false, result.diagnostics["strength_is_probability"])
    }

    @Test
    fun decisionGateBlocksMissingAndExhaustedEvidence() {
        assertEquals("NO_RECENT_EVIDENCE", DecisionGate.block(false, true, "CONTINUING", 0.0, 80.0, 90.0, "NONE"))
        assertEquals("REGISTRATION_UNSTABLE", DecisionGate.block(true, false, "CONTINUING", 0.0, 80.0, 90.0, "NONE"))
        assertEquals("EXHAUSTION", DecisionGate.block(true, true, "EXHAUSTION", 0.0, 80.0, 90.0, "NONE"))
        assertEquals("NOISE", DecisionGate.block(true, true, "NOISE", 0.0, 80.0, 90.0, "NONE"))
        assertEquals("EXPERIENCE_AVOID", DecisionGate.block(true, true, "CONTINUING", 0.0, 80.0, 90.0, "AVOID"))
        assertEquals("", DecisionGate.block(true, true, "CONTINUING", 0.2, 80.0, 90.0, "NONE"))
    }

    @Test
    fun attachedExperienceFileDoesNotBoostAndIsMostlyNoRecent() {
        val text = javaClass.classLoader!!.getResourceAsStream("experience_20261008.json")!!.bufferedReader().readText()
        assertEquals(70, Regex(""""total_results"\s*:\s*(\d+)""").find(text)!!.groupValues[1].toInt())
        val recent = Regex(""""recent"\s*:\s*"([WL]+)"""").find(text)!!.groupValues[1]
        assertEquals(40, recent.length)
        assertEquals(23, recent.count { it == 'W' })
        assertEquals(17, recent.count { it == 'L' })
        val cells = HashMap<String, DoubleArray>()
        val re = Regex(""""k"\s*:\s*"([^"]+)"\s*,\s*"w"\s*:\s*(-?[0-9.]+)\s*,\s*"l"\s*:\s*(-?[0-9.]+)""")
        for (m in re.findAll(text)) cells[m.groupValues[1]] = doubleArrayOf(m.groupValues[2].toDouble(), m.groupValues[3].toDouble())
        assertEquals(19, cells.size)
        var noRecent = 0.0; var continuing = 0.0
        for ((k, v) in cells) if (k.count { it == '|' } == 1) {
            if (k.endsWith("|NO_RECENT")) noRecent += v[0] + v[1]
            if (k.endsWith("|CONTINUING")) continuing += v[0] + v[1]
        }
        assertTrue(noRecent / (noRecent + continuing) > 0.90)
        for (k in cells.keys.filter { it.count { c -> c == '|' } == 3 }) {
            val p = k.split("|")
            val advice = ExperienceMath.advise(cells, p[0], p[1], p[2], p[3])
            assertEquals(k, "NONE", advice.action)
            assertNotEquals("BOOST", advice.action)
        }
    }

    @Test
    fun uncalibratedCellIsNotBoostedButABadCellCanStillAvoid() {
        val hot = mapOf("UP|CONTINUING|TREND|E_MID" to doubleArrayOf(40.0, 2.0))
        val hotAdvice = ExperienceMath.advise(hot, "UP", "CONTINUING", "TREND", "E_MID")
        assertEquals("NONE", hotAdvice.action)
        assertEquals(0.0, hotAdvice.strengthDelta, 1e-9)
        assertEquals("UNCALIBRATED_NO_BOOST", hotAdvice.note)
        val cold = mapOf("UP|CONTINUING|TREND|E_HIGH" to doubleArrayOf(1.0, 30.0))
        val coldAdvice = ExperienceMath.advise(cold, "UP", "CONTINUING", "TREND", "E_HIGH")
        assertEquals("AVOID", coldAdvice.action)
        val damp = mapOf("DOWN|CONTINUING|TREND|E_MID" to doubleArrayOf(8.0, 14.0))
        assertEquals("DAMP", ExperienceMath.advise(damp, "DOWN", "CONTINUING", "TREND", "E_MID").action)
        assertFalse(ExperienceMath.trainsWeights("NO_RECENT"))
        assertTrue(ExperienceMath.trainsWeights("CONTINUING"))
        assertTrue(ExperienceMath.alreadyApplied(listOf("a", "b"), "a"))
        assertFalse(ExperienceMath.alreadyApplied(listOf("a"), ""))
    }

    @Test
    fun pendingTradeRoundTripAndRemoval() {
        val dir = File(System.getProperty("java.io.tmpdir"), "lsa-pending-" + System.nanoTime())
        val store = PendingTradeStore(dir)
        val p = PendingTradeStore.Pending("sig-1", "UP", "CONTINUING", "TREND", "E_MID", 61, mapOf("recent_valid" to "true"))
        store.upsert(p)
        val back = store.all()
        assertEquals(1, back.size)
        assertEquals("sig-1", back[0].signalId)
        assertEquals("CONTINUING", back[0].state)
        assertEquals("true", back[0].fields["recent_valid"])
        store.upsert(p.copy(score = 62))
        assertEquals(1, store.all().size)
        assertEquals(62, store.all()[0].score)
        store.remove("sig-1")
        assertTrue(store.all().isEmpty())
        dir.deleteRecursively()
    }

    /** Constant slope, so the last seconds are still moving. A sine stall is a different case. */
    private fun rampFrame(shift: Int): DoubleArray = DoubleArray(160) { i -> 20.0 + 0.35 * (i + shift) }

    private fun feedSmooth(n: Int): ChangeEngine {
        val ce = ChangeEngine()
        for (i in 0 until n) ce.update(i.toDouble(), rampFrame(i * 2))
        return ce
    }

    private fun none() = object : ExperienceSource {
        override fun advise(action: String, state: String, regime: String, band: String) =
            ExperienceStore.Advice("NONE", 0.0, 0.0, 0.5, 0.0, 1.0, -1, "", "TEST", 0.5)
    }
}

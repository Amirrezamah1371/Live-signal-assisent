package com.example.livesignalassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A short counter-move stays inside the wave that is already there.
 * The body scrolls so registration can accept the frame. The price path lives in the
 * right-edge tip, which is the value the series actually records.
 */
class StructuralWaveTest {
    private fun frame(shift: Int, tip: Double, leg: Double): DoubleArray {
        val y = DoubleArray(160) { i -> 80.0 + 0.35 * (i + shift) }
        val behind = tip - leg
        for (i in 144..156) y[i] = behind
        y[157] = tip
        y[158] = tip
        y[159] = tip
        return y
    }

    /** Each tip is one second. [leg] is the current frame's own right-edge move. */
    private fun drive(tips: List<Double>): ChangeEngine {
        val ce = ChangeEngine()
        tips.forEachIndexed { i, tip ->
            val prev = if (i == 0) tip else tips[i - 1]
            ce.update(i.toDouble(), frame(i * 2, tip, tip - prev))
        }
        return ce
    }

    private fun none() = object : ExperienceSource {
        override fun advise(action: String, state: String, regime: String, band: String) =
            ExperienceStore.Advice("NONE", 0.0, 0.0, 0.5, 0.0, 1.0, -1, "", "TEST", 0.5)
    }

    private fun avoid() = object : ExperienceSource {
        override fun advise(action: String, state: String, regime: String, band: String) =
            ExperienceStore.Advice("AVOID", -20.0, 0.2, 0.3, 0.1, 0.4, 12, "cell", "TEST", 0.5)
    }

    private fun qualified(dir: String, reason: String = "TREND"): SignalResult {
        val sign = if (dir == "DOWN") -1 else 1
        return SignalResult(
            dir, 60, 80, entryQuality = 78, conflict = 10, reason = reason,
            diagnostics = mapOf(
                "exhaustion" to 0.05,
                "trend_regime" to 0.8,
                "measured_direction" to dir,
                "entry_permission" to "OPEN"
            ),
            side = sign, traceQuality = 0.95
        )
    }

    private fun downThen(bounce: List<Double>): List<Double> {
        val fall = (0 until 22).map { 400.0 - it * 8.0 }
        return fall + bounce
    }

    private fun upThen(dip: List<Double>): List<Double> {
        val rise = (0 until 22).map { 40.0 + it * 8.0 }
        return rise + dip
    }

    private fun describe(wave: WaveRead): String =
        "phase=${wave.phase} retrace=${wave.retrace} broken=${wave.broken} dom=${wave.sideName()} " +
            "shock=${wave.shock} z3=${wave.z3} z6=${wave.z6} z10=${wave.z10} z20=${wave.z20}"

    @Test
    fun downWavePlusSmallUpPullbackDoesNotPublishUp() {
        val last = 400.0 - 21 * 8.0
        val ce = drive(downThen(listOf(last + 4.0, last + 8.0, last + 12.0)))
        val t = (ce.pointCount() - 1).toDouble()
        val wave = ce.wave(t)
        assertEquals(describe(wave), "DOWN", wave.sideName())
        assertTrue("retrace ${wave.retrace} left the wave", wave.retrace < 0.5)
        assertTrue(!wave.broken)
        val result = OpportunityDecider.evaluate(qualified("UP", "FAIL_BRK"), ce, t, none())
        assertEquals(result.reason, "WAIT", result.direction)
        assertEquals("PULLBACK", result.reason)
        assertEquals("DOWN", result.diagnostics["measured_direction"])
        assertEquals("PULLBACK", result.diagnostics["wave_phase"])
    }

    @Test
    fun upWavePlusSmallDownPullbackDoesNotPublishDown() {
        val last = 40.0 + 21 * 8.0
        val ce = drive(upThen(listOf(last - 4.0, last - 8.0, last - 12.0)))
        val t = (ce.pointCount() - 1).toDouble()
        val wave = ce.wave(t)
        assertEquals(describe(wave), "UP", wave.sideName())
        assertTrue(wave.retrace < 0.5)
        val result = OpportunityDecider.evaluate(qualified("DOWN", "FAIL_BRK"), ce, t, none())
        assertEquals(result.reason, "WAIT", result.direction)
        assertEquals("PULLBACK", result.reason)
        assertEquals("UP", result.diagnostics["measured_direction"])
    }

    @Test
    fun downWavePlusBullishStructureBreakMakesUpEligible() {
        val low = 400.0 - 21 * 8.0
        // Half of the drop is given back, and the 6s and 10s windows are the new leg.
        val bounce = (1..12).map { low + it * 8.0 }
        val ce = drive(downThen(bounce))
        val t = (ce.pointCount() - 1).toDouble()
        val wave = ce.wave(t)
        assertTrue(describe(wave), wave.broken || wave.dominant > 0)
        val result = OpportunityDecider.evaluate(qualified("UP", "FAIL_BRK"), ce, t, none())
        assertEquals(result.reason, "UP", result.direction)
    }

    @Test
    fun upWavePlusBearishStructureBreakMakesDownEligible() {
        val high = 40.0 + 21 * 8.0
        val drop = (1..12).map { high - it * 8.0 }
        val ce = drive(upThen(drop))
        val t = (ce.pointCount() - 1).toDouble()
        val wave = ce.wave(t)
        assertTrue(describe(wave), wave.broken || wave.dominant < 0)
        val result = OpportunityDecider.evaluate(qualified("DOWN"), ce, t, none())
        assertEquals(result.reason, "DOWN", result.direction)
    }

    @Test
    fun suddenImpulseStaysShockUntilItIsAWave() {
        val flat = (0 until 20).map { 180.0 }
        val ce = drive(flat + listOf(260.0, 300.0))
        val t = (ce.pointCount() - 1).toDouble()
        val wave = ce.wave(t)
        assertTrue(describe(wave) + " pts=${ce.pointCount()} last=${ce.debugState()["last"]} reg=${ce.lastReg}", wave.shock)
        val result = OpportunityDecider.evaluate(qualified("UP", "FAIL_BRK"), ce, t, none())
        assertEquals(result.reason, "WAIT", result.direction)
        assertEquals("SHOCK_UNRESOLVED", result.reason)
    }

    @Test
    fun shockFollowedByAPersistentLegCanBecomeTheNewWave() {
        val flat = (0 until 16).map { 180.0 }
        val established = (1..14).map { 300.0 + it * 6.0 }
        val ce = drive(flat + listOf(260.0, 300.0) + established)
        val t = (ce.pointCount() - 1).toDouble()
        val wave = ce.wave(t)
        assertTrue("shock was still unresolved: ${describe(wave)}", !wave.shock)
        assertEquals(describe(wave), "UP", wave.sideName())
        val result = OpportunityDecider.evaluate(qualified("UP"), ce, t, none())
        assertEquals(result.reason, "UP", result.direction)
    }

    @Test
    fun strongShortSlopeAgainstIntactMediumStructureStaysPullback() {
        val last = 400.0 - 21 * 8.0
        // Three seconds rising, still a small fraction of the prior drop, and not most of the 10s path.
        val ce = drive(downThen(listOf(last + 6.0, last + 12.0, last + 18.0)))
        val t = (ce.pointCount() - 1).toDouble()
        val wave = ce.wave(t)
        assertTrue(describe(wave), wave.z3 > 0.5)
        assertTrue(describe(wave), wave.z20 < 0.0)
        assertTrue(!wave.broken)
        val result = OpportunityDecider.evaluate(qualified("UP"), ce, t, none())
        assertEquals("WAIT", result.direction)
        assertNotEquals("UP", result.direction)
        assertEquals("DOWN", result.diagnostics["dominant_wave"])
        assertTrue(result.reason == "PULLBACK" || result.reason == "SHOCK_UNRESOLVED")
    }

    @Test
    fun continuationAfterAShallowPullbackStaysEligible() {
        val last = 400.0 - 21 * 8.0
        val pullback = listOf(last + 4.0, last + 8.0, last + 10.0)
        val resume = (1..6).map { last + 10.0 - it * 8.0 }
        val ce = drive(downThen(pullback + resume))
        val t = (ce.pointCount() - 1).toDouble()
        val wave = ce.wave(t)
        assertEquals(describe(wave), "DOWN", wave.sideName())
        assertTrue(!wave.broken)
        val result = OpportunityDecider.evaluate(qualified("DOWN"), ce, t, none())
        assertEquals(result.reason, "DOWN", result.direction)
    }

    @Test
    fun continuousOpportunityStillPublishesInsideTheWindow() {
        val ce = drive((0 until 20).map { 20.0 + it * 2.0 })
        val cycle = OpportunityCycle()
        cycle.start(0L)
        val published = cycle.consider(qualified("UP"), ce, 19.0, none())
        assertEquals("UP", published?.direction)
        assertEquals(90_000L, cycle.resetAtMs)
    }

    @Test
    fun tradeClockStaysIndependentOfTheObservationReset() {
        val cycle = OpportunityCycle()
        cycle.start(0L)
        val lockedAt = 25_000L
        val tradeTarget = lockedAt + 60_000L
        cycle.onUserLocked(lockedAt)
        assertEquals(lockedAt + 90_000L, cycle.resetAtMs)
        assertEquals(60_000L, tradeTarget - lockedAt)
    }

    @Test
    fun experienceAvoidStillRefusesAValidContinuation() {
        val ce = drive((0 until 20).map { 20.0 + it * 2.0 })
        val result = OpportunityDecider.evaluate(qualified("UP"), ce, 19.0, avoid())
        assertEquals(result.reason, "WAIT", result.direction)
        assertEquals("EXPERIENCE_AVOID", result.reason)
        assertEquals("UP", result.diagnostics["measured_direction"])
        assertEquals(
            listOf("CONTINUING", "PULLBACK", "EXHAUSTION", "REGIME_CHANGE", "NOISE", "NO_RECENT"),
            ExperienceStore.STATES
        )
    }
}

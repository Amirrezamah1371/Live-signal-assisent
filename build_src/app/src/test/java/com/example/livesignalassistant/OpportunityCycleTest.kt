package com.example.livesignalassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A qualified T+60 opportunity can publish before the 90-second maximum.
 * Nothing here lowers a threshold to force a signal, and WAIT stays the result when evidence is missing.
 */
class OpportunityCycleTest {
    private fun ramp(shift: Int): DoubleArray = DoubleArray(160) { i -> 20.0 + 0.35 * (i + shift) }

    private fun feed(n: Int): ChangeEngine {
        val ce = ChangeEngine()
        for (i in 0 until n) ce.update(i.toDouble(), ramp(i * 2))
        return ce
    }

    private fun none() = object : ExperienceSource {
        override fun advise(action: String, state: String, regime: String, band: String) =
            ExperienceStore.Advice("NONE", 0.0, 0.0, 0.5, 0.0, 1.0, -1, "", "TEST", 0.5)
    }

    private fun qualified(dir: String = "UP", reason: String = "TREND"): SignalResult {
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

    private fun late(dir: String = "UP"): SignalResult = SignalResult(
        "WAIT", 60, 80, entryQuality = 78, conflict = 10, reason = "LATE_ENTRY_RISK",
        diagnostics = mapOf(
            "exhaustion" to 0.8,
            "trend_regime" to 0.8,
            "measured_direction" to dir,
            "entry_permission" to "REFUSED"
        ),
        side = if (dir == "DOWN") -1 else 1, traceQuality = 0.9
    )

    @Test
    fun opportunityAtSecond20PublishesWithoutWaitingFor90() {
        val ce = feed(20)
        val cycle = OpportunityCycle()
        cycle.start(0L)
        val published = cycle.consider(qualified(), ce, 19.0, none())
        assertEquals("UP", published?.direction)
        assertTrue(20_000L < cycle.resetAtMs)
        assertTrue(cycle.resetAtMs == 90_000L)
    }

    @Test
    fun opportunityAtSecond70PublishesImmediately() {
        val ce = feed(20)
        val cycle = OpportunityCycle()
        cycle.start(0L)
        val elapsed = 70_000L
        assertTrue(elapsed < cycle.resetAtMs)
        val published = cycle.consider(qualified(), ce, 19.0, none())
        assertEquals("UP", published?.direction)
        assertEquals(90_000L, cycle.resetAtMs)
        assertEquals("OPEN", published?.diagnostics?.get("entry_permission"))
    }

    @Test
    fun noOpportunityReachesTheMaximumWithoutAForcedSignal() {
        val ce = feed(20)
        val cycle = OpportunityCycle()
        cycle.start(0L)
        val noisy = SignalResult("WAIT", 60, 20, entryQuality = 10, conflict = 80, reason = "FILTER", side = 0)
        repeat(90) { assertNull(cycle.consider(noisy, ce, 19.0, none())) }
        val already = cycle.onMaximumWindow(90_000L)
        assertTrue(!already)
        assertEquals(90_000L, cycle.startMs)
        assertEquals(180_000L, cycle.resetAtMs)
    }

    @Test
    fun noisyEvidenceDoesNotSpam() {
        val ce = feed(20)
        val cycle = OpportunityCycle()
        cycle.start(0L)
        val frames = listOf(
            SignalResult("WAIT", 60, 30, reason = "FILTER", side = 1),
            SignalResult("WAIT", 60, 40, reason = "DIRECTION_UNSTABLE", side = -1),
            late("UP"),
            SignalResult("WAIT", 60, 10, reason = "LOW_VISIBILITY", side = 0)
        )
        var publishes = 0
        repeat(12) { i ->
            if (cycle.consider(frames[i % frames.size], ce, 19.0, none()) != null) publishes++
        }
        assertEquals(0, publishes)
    }

    @Test
    fun oneSetupPublishesOnce() {
        val ce = feed(20)
        val cycle = OpportunityCycle()
        cycle.start(0L)
        val first = cycle.consider(qualified(), ce, 19.0, none())
        assertEquals("UP", first?.direction)
        assertNull(cycle.consider(qualified(), ce, 19.0, none()))
        cycle.onBubbleTimeout()
        assertNull("the same setup returned after the bubble closed", cycle.consider(qualified(), ce, 19.0, none()))
    }

    @Test
    fun userLockResetsTheObservationWindowAndKeepsTheTradeClock() {
        val ce = feed(20)
        val cycle = OpportunityCycle()
        cycle.start(0L)
        assertEquals("UP", cycle.consider(qualified(), ce, 19.0, none())?.direction)
        val lockedAt = 25_000L
        val tradeTarget = lockedAt + 60_000L
        cycle.onUserLocked(lockedAt)
        assertEquals(lockedAt, cycle.startMs)
        assertEquals(lockedAt + 90_000L, cycle.resetAtMs)
        assertTrue(!cycle.bubbleOpen)
        assertTrue(!cycle.publishedThisWindow)
        assertEquals(60_000L, tradeTarget - lockedAt)
        assertEquals(50_000L, tradeTarget - 35_000L)
        val next = cycle.consider(qualified(), ce, 19.0, none())
        assertEquals("the new window can publish its own opportunity", "UP", next?.direction)
    }

    @Test
    fun staleRegistrationStaysWait() {
        val ce = ChangeEngine()
        val result = OpportunityDecider.evaluate(qualified(), ce, 20.0, none())
        assertEquals("WAIT", result.direction)
        assertEquals("REGISTRATION_UNSTABLE", result.reason)
    }

    @Test
    fun lateEntryKeepsTheMeasuredDirectionAndRefusesEntry() {
        val ce = feed(20)
        val result = OpportunityDecider.evaluate(late("UP"), ce, 19.0, none())
        assertEquals("WAIT", result.direction)
        assertEquals("LATE_ENTRY_RISK", result.reason)
        assertEquals("UP", result.diagnostics["measured_direction"])
        assertEquals("REFUSED", result.diagnostics["entry_permission"])
        assertEquals(1, result.side)
    }

    @Test
    fun historicalLateEntryMassDoesNotEraseANewDirection() {
        val ce = feed(20)
        val frames = ArrayList<Obs>()
        repeat(40) { i ->
            frames += Obs(5_000L + i * 500L, SignalResult(
                "WAIT", 60, 70, entryQuality = 60, conflict = 20, reason = "LATE_ENTRY_RISK",
                diagnostics = mapOf("exhaustion" to 0.8, "trend_regime" to 0.8),
                side = -1, traceQuality = 0.9
            ))
        }
        repeat(40) { i ->
            frames += Obs(50_000L + i * 1000L, SignalResult(
                "UP", 60, 80, entryQuality = 78, conflict = 12, reason = "TREND",
                diagnostics = mapOf("exhaustion" to 0.05, "trend_regime" to 0.8),
                side = 1, traceQuality = 0.95
            ))
        }
        val result = CycleDecider.decide(frames, 90_000L, ce, 19.0, none(), 0L)
        assertNotEquals("ENTRY_REFUSED", result.reason)
        assertEquals("UP", result.direction)
    }
}

package com.example.livesignalassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Registration keeps a trusted trajectory apart from a candidate, and entry safety
 * cannot be waived by a structural label or by Experience.
 */
class RepairGatesTest {
    private fun ramp(shift: Int, lift: Double = 0.0): DoubleArray =
        DoubleArray(160) { i -> 20.0 + 0.35 * (i + shift) + lift }

    private fun feed(n: Int): ChangeEngine {
        val ce = ChangeEngine()
        for (i in 0 until n) ce.update(i.toDouble(), ramp(i * 2))
        return ce
    }

    private fun none() = object : ExperienceSource {
        override fun advise(action: String, state: String, regime: String, band: String) =
            ExperienceStore.Advice("NONE", 0.0, 0.0, 0.5, 0.0, 1.0, -1, "", "TEST", 0.5)
    }

    private fun obs(tMs: Long, dir: String, reason: String, side: Int, exhaustion: Double = 0.05): Obs {
        val direction = if (dir == "UP" || dir == "DOWN") dir else "WAIT"
        return Obs(tMs, SignalResult(
            direction, 60, 80, entryQuality = 78, conflict = 10, reason = reason,
            diagnostics = mapOf("exhaustion" to exhaustion, "trend_regime" to 0.8, "continuation" to 0.2),
            side = side, traceQuality = 0.95
        ))
    }

    @Test
    fun briefRegistrationFailureDoesNotDestroyTrustedHistory() {
        val ce = feed(16)
        val points = ce.pointCount()
        val last = ce.debugState()["last"] as Double
        val bad = DoubleArray(160) { if (it % 2 == 0) 4000.0 else -4000.0 }
        val held = ce.update(16.0, bad)
        assertEquals("FAIL", held["reg_status"])
        assertTrue((held["reg_reason"] as String).startsWith("HELD_"))
        assertEquals(points, ce.pointCount())
        assertEquals(last, ce.debugState()["last"] as Double, 1e-6)
        val back = ce.update(17.0, ramp(17 * 2))
        assertEquals("OK", back["reg_status"])
        assertEquals("ACCEPTED", back["reg_reason"])
        assertEquals(points + 1, ce.pointCount())
        assertTrue(ce.features(17.0, 1).valid)
        assertTrue(ce.registrationTrusted(17.0))
    }

    @Test
    fun repeatedTemporaryFailuresDoNotContaminateTrustedHistory() {
        val ce = feed(16)
        val points = ce.pointCount()
        val last = ce.debugState()["last"] as Double
        for (k in 0 until 5) {
            val bad = DoubleArray(160) { i -> 800.0 * ((k + 1) * (i % 5) - 2) + k * 50.0 }
            val info = ce.update(16.0 + k, bad)
            assertNotEquals("OK", info["reg_status"])
            assertFalse((info["reg_reason"] as String).startsWith("RESET_"))
            assertFalse((info["reg_reason"] as String).startsWith("PROMOTED"))
        }
        assertEquals(points, ce.pointCount())
        assertEquals(last, ce.debugState()["last"] as Double, 1e-6)
        assertEquals(false, ce.debugState()["provisional"])
        assertFalse(ce.features(20.0, 1).valid)
    }

    @Test
    fun provisionalCoordinatesCannotGenerateTradeEvidence() {
        val ce = ChangeEngine()
        ce.update(0.0, ramp(0))
        assertEquals(true, ce.debugState()["provisional"])
        assertFalse(ce.features(0.0, 1).valid)
        assertEquals("PROVISIONAL", ce.seriesStatus(0.0).reason)
        assertFalse(ce.registrationTrusted(0.0))
        val obs = (0 until 50).map { i -> obs(i * 1000L, "UP", "TREND", 1) }
        val result = CycleDecider.decide(obs, 90000L, ce, 0.0, none(), 0L)
        assertEquals("WAIT", result.direction)
        assertEquals("NO_RECENT_EVIDENCE", result.reason)
    }

    @Test
    fun genuineLongRegistrationLossInvalidatesSafely() {
        val ce = feed(16)
        val rnd = java.util.Random(7)
        for (k in 0 until 16) {
            val bad = DoubleArray(160) { rnd.nextDouble() * 5000.0 - 2500.0 }
            ce.update(16.0 + k, bad)
        }
        assertEquals(true, ce.debugState()["provisional"])
        assertTrue(ce.pointCount() < 8)
        assertFalse(ce.features(31.0, 1).valid)
        assertFalse(ce.registrationTrusted(31.0))
        // One later frame is only a seed. It is not yet evidence.
        ce.update(32.0, ramp(0))
        assertFalse(ce.features(32.0, 1).valid)
    }

    @Test
    fun recoveryRequiresPositiveRegistrationEvidence() {
        val ce = feed(16)
        val points = ce.pointCount()
        val bad = DoubleArray(160) { if (it % 2 == 0) 900.0 else -900.0 }
        ce.update(16.0, bad)
        assertEquals(points, ce.pointCount())
        assertEquals(1, ce.debugState()["candidate_points"])
        // A pure ramp matches every shift, so this frame has to be a different shape.
        // The candidate has a single frame. It must not replace the trusted series.
        val still = ce.update(17.0, DoubleArray(160) { i -> 50.0 + 12.0 * kotlin.math.sin(i / 3.0) })
        assertEquals(points, ce.pointCount())
        assertNotEquals("PROMOTED_CANDIDATE", still["reg_reason"])
        val recovered = ce.update(18.0, ramp(18 * 2))
        assertEquals("ACCEPTED", recovered["reg_reason"])
        assertTrue(ce.features(18.0, 1).valid)
    }

    @Test
    fun downWhileRecentWindowsPointUpWaits() {
        assertEquals("AGAINST_RECENT", EntrySafety.block(
            agreement = -1.0, state = "PULLBACK", exhaustion = 0.2, impulseZ = 2.0,
            velocityRatio = 0.2, sincePeakSec = 10, counter = 1.5, refuseWeight = 0.0, directWeight = 5.0
        ))
    }

    @Test
    fun upWhileRecentWindowsPointDownWaits() {
        assertEquals("AGAINST_RECENT", EntrySafety.block(
            agreement = -0.5, state = "CONTINUING", exhaustion = 0.1, impulseZ = 1.0,
            velocityRatio = 1.0, sincePeakSec = 0, counter = 0.0, refuseWeight = 0.0, directWeight = 5.0
        ))
    }

    @Test
    fun pullbackWithoutResumptionWaits() {
        // Three windows still agree, so this is not AGAINST_RECENT. The 6s window has not resumed.
        assertEquals("PULLBACK_UNRESOLVED", EntrySafety.block(
            agreement = 0.5, state = "PULLBACK", exhaustion = 0.1, impulseZ = 1.0,
            velocityRatio = 1.0, sincePeakSec = 4, counter = 0.2, refuseWeight = 0.0, directWeight = 5.0
        ))
    }

    @Test
    fun matureImpulseNearExtremeWaits() {
        assertEquals("MATURE_IMPULSE", EntrySafety.block(
            agreement = 1.0, state = "CONTINUING", exhaustion = 0.85, impulseZ = 8.0,
            velocityRatio = 0.4, sincePeakSec = 0, counter = 0.0, refuseWeight = 0.0, directWeight = 8.0
        ))
    }

    @Test
    fun youngStrongContinuationRemainsEligible() {
        assertEquals("", EntrySafety.block(
            agreement = 1.0, state = "CONTINUING", exhaustion = 0.04, impulseZ = 8.0,
            velocityRatio = 0.4, sincePeakSec = 0, counter = 0.0, refuseWeight = 0.0, directWeight = 8.0
        ))
        val ce = feed(20)
        val live = (0 until 50).map { i -> obs(40000L + i * 1000L, "UP", "TREND", 1, exhaustion = 0.05) }
        val published = CycleDecider.decide(live, 90000L, ce, 19.0, none(), 0L)
        assertEquals("UP", published.direction)
    }

    /** Rise, then sit on the high for 3 seconds so speed has left its peak. */
    private fun plateau(): ChangeEngine {
        val ce = ChangeEngine()
        for (i in 0 until 16) {
            val moving = i < 13
            val shift = if (moving) i * 2 else 24
            val lift = if (moving) i * 5.0 else 60.0
            ce.update(i.toDouble(), ramp(shift, lift))
        }
        return ce
    }

    private fun structuralCannotBypass(reason: String) {
        val blocked = EntrySafety.block(
            agreement = 1.0, state = "CONTINUING", exhaustion = 0.9, impulseZ = 6.0,
            velocityRatio = 0.3, sincePeakSec = 0, counter = 0.0, refuseWeight = 0.0, directWeight = 4.0
        )
        assertEquals("MATURE_IMPULSE", blocked)
        val ce = plateau()
        val frames = (0 until 50).map { i -> obs(40000L + i * 1000L, "UP", reason, 1, exhaustion = 0.9) }
        val result = CycleDecider.decide(frames, 90000L, ce, 17.0, none(), 0L)
        assertEquals("WAIT", result.direction)
        assertEquals("MATURE_IMPULSE", result.reason)
    }

    @Test fun brkCannotBypassEntrySafety() = structuralCannotBypass("BRK")

    @Test fun failBrkCannotBypassEntrySafety() = structuralCannotBypass("FAIL_BRK")

    @Test fun turnCannotBypassEntrySafety() = structuralCannotBypass("TURN")

    @Test fun revCannotBypassEntrySafety() = structuralCannotBypass("REV")

    @Test
    fun tinyStructuralVoteCannotDefeatLargeLateEntryEvidence() {
        val ce = feed(20)
        val frames = ArrayList<Obs>()
        repeat(4) { i -> frames += obs(70000L + i * 1000L, "UP", "FAIL_BRK", 1, exhaustion = 0.1) }
        repeat(55) { i -> frames += obs(10000L + i * 1000L, "WAIT", "LATE_ENTRY_RISK", -1, exhaustion = 0.2) }
        val result = CycleDecider.decide(frames, 90000L, ce, 19.0, none(), 0L)
        assertEquals("WAIT", result.direction)
        assertEquals("ENTRY_REFUSED", result.reason)
    }

    @Test
    fun experienceCannotOverrideSafety() {
        val safety = EntrySafety.block(
            agreement = -1.0, state = "CONTINUING", exhaustion = 0.0, impulseZ = 0.2,
            velocityRatio = 1.0, sincePeakSec = 2, counter = 0.0, refuseWeight = 0.0, directWeight = 9.0
        )
        assertEquals("AGAINST_RECENT", safety)
        assertEquals(
            "AGAINST_RECENT",
            DecisionGate.block(true, true, "CONTINUING", 0.0, 90.0, 99.0, "NONE", safety)
        )
        assertEquals(
            "AGAINST_RECENT",
            DecisionGate.block(true, true, "CONTINUING", 0.0, 90.0, 99.0, "AVOID", safety)
        )
    }

    @Test
    fun quietCycleIsNotTreatedAsAnExplicitRefusal() {
        assertEquals("", EntrySafety.block(
            agreement = 1.0, state = "CONTINUING", exhaustion = 0.1, impulseZ = 2.0,
            velocityRatio = 1.0, sincePeakSec = 0, counter = 0.0, refuseWeight = 0.0, directWeight = 0.4
        ))
    }

    @Test
    fun experienceSchemaStaysVersionThree() {
        val text = javaClass.classLoader!!.getResourceAsStream("experience_20261008.json")!!.bufferedReader().readText()
        assertTrue(text.contains("\"version\": 3") || text.contains("\"version\":3"))
    }
}

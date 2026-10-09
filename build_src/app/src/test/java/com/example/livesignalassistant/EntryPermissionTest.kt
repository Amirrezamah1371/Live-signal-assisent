package com.example.livesignalassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Entry permission is a separate question from the dominant wave.
 * These fixtures are shapes. They do not name a session, an asset, or an outcome.
 */
class EntryPermissionTest {
    private fun frame(shift: Int, tip: Double, leg: Double): DoubleArray {
        val y = DoubleArray(160) { i -> 80.0 + 0.35 * (i + shift) }
        val behind = tip - leg
        for (i in 144..156) y[i] = behind
        y[157] = tip
        y[158] = tip
        y[159] = tip
        return y
    }

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
            ExperienceStore.Advice("NONE", 40.0, 0.0, 0.5, 0.0, 1.0, -1, "", "TEST", 0.5)
    }

    private fun avoid() = object : ExperienceSource {
        override fun advise(action: String, state: String, regime: String, band: String) =
            ExperienceStore.Advice("AVOID", -20.0, 0.2, 0.3, 0.1, 0.4, 12, "cell", "TEST", 0.5)
    }

    private fun qualified(
        dir: String,
        reason: String = "TREND",
        exhaustion: Double = 0.05,
        rangePosition: Double = 0.40
    ): SignalResult {
        val sign = if (dir == "DOWN") -1 else 1
        return SignalResult(
            dir, 60, 80, entryQuality = 78, conflict = 10, reason = reason,
            diagnostics = mapOf(
                "exhaustion" to exhaustion,
                "range_position" to rangePosition,
                "trend_regime" to 0.8,
                "measured_direction" to dir,
                "entry_permission" to "OPEN"
            ),
            side = sign, traceQuality = 0.95
        )
    }

    private fun tOf(ce: ChangeEngine) = (ce.pointCount() - 1).toDouble()

    private fun wave(dominant: Int, retrace: Double, broken: Boolean = false, shock: Boolean = false, phase: String = "CONTINUING", z3: Double = 1.2, z6: Double = 1.1, z10: Double = 1.0, z20: Double = 1.4): WaveRead {
        val signed = dominant.toDouble()
        return WaveRead(true, dominant, phase, retrace, broken, shock, dominant, signed * z3, signed * z6, signed * z10, signed * z20)
    }

    @Test
    fun earlyHealthyUpContinuationIsAValidEntry() {
        val ce = drive((0 until 24).map { 40.0 + it * 6.0 })
        val t = tOf(ce)
        val w = ce.wave(t)
        assertEquals("UP", w.sideName())
        assertTrue(w.retrace < 0.5)
        val result = OpportunityDecider.evaluate(qualified("UP"), ce, t, none())
        assertEquals(result.reason, "UP", result.direction)
        assertEquals(EntryPermission.VALID_UP, result.diagnostics["entry_state"])
    }

    @Test
    fun lateExhaustedUpContinuationIsNotAnEntry() {
        val spent = wave(1, retrace = 0.59, z20 = -0.37)
        assertEquals(
            EntryPermission.WAIT_EXHAUSTED,
            EntryPermission.publication(1, spent, 14.0, 3.0, 1.0, 0.26, 0.0)
        )
        val ce = drive(spentSwing(up = true))
        val t = tOf(ce)
        val w = ce.wave(t)
        assertTrue(
            "retrace=${w.retrace} z3=${w.z3} z6=${w.z6} dom=${w.dominant} broken=${w.broken} shock=${w.shock}",
            w.dominant == 1 && !w.broken && w.retrace >= 0.5 && !w.shock
        )
        val result = OpportunityDecider.evaluate(qualified("UP"), ce, t, none())
        assertEquals("UP", result.diagnostics["measured_direction"])
        assertEquals("WAIT", result.direction)
        assertEquals(EntryPermission.WAIT_EXHAUSTED, result.reason)
    }

    @Test
    fun earlyHealthyDownContinuationIsAValidEntry() {
        val ce = drive((0 until 24).map { 400.0 - it * 6.0 })
        val t = tOf(ce)
        val w = ce.wave(t)
        assertEquals("DOWN", w.sideName())
        assertTrue(w.retrace < 0.5)
        val result = OpportunityDecider.evaluate(qualified("DOWN"), ce, t, none())
        assertEquals(result.reason, "DOWN", result.direction)
        assertEquals(EntryPermission.VALID_DOWN, result.diagnostics["entry_state"])
    }

    @Test
    fun lateExhaustedDownContinuationIsNotAnEntry() {
        val spent = wave(-1, retrace = 0.62)
        assertEquals(
            EntryPermission.WAIT_EXHAUSTED,
            EntryPermission.publication(-1, spent, -12.0, -1.0, 1.0, 0.70, 0.1)
        )
        val ce = drive(spentSwing(up = false))
        val w = ce.wave(tOf(ce))
        assertTrue(
            "retrace=${w.retrace} z3=${w.z3} z6=${w.z6} dom=${w.dominant} broken=${w.broken} shock=${w.shock}",
            w.dominant == -1 && !w.broken && w.retrace >= 0.5 && !w.shock
        )
        val result = OpportunityDecider.evaluate(qualified("DOWN"), ce, tOf(ce), none())
        assertEquals("DOWN", result.diagnostics["measured_direction"])
        assertEquals("WAIT", result.direction)
        assertEquals(EntryPermission.WAIT_EXHAUSTED, result.reason)
    }

    /** A swing that has already given back half its range while the latest seconds still agree with the side. */
    private fun spentSwing(up: Boolean): List<Double> {
        val window = ArrayList<Double>()
        var p = 100.0
        window += p
        repeat(8) { p += 6.0; window += p }
        repeat(5) { p -= 10.0; window += p }
        repeat(6) { p += 3.0; window += p }
        val prefix = (0 until 10).map { 40.0 + it * 6.0 }
        val raw = prefix + window
        return if (up) raw else raw.map { 600.0 - it }
    }

    @Test
    fun shallowPullbackDoesNotFlipTheSide() {
        val last = 400.0 - 21 * 8.0
        val ce = drive((0 until 22).map { 400.0 - it * 8.0 } + listOf(last + 4.0, last + 8.0, last + 12.0))
        val t = tOf(ce)
        val w = ce.wave(t)
        assertEquals("DOWN", w.sideName())
        assertTrue(w.retrace < 0.5)
        val result = OpportunityDecider.evaluate(qualified("UP", "FAIL_BRK"), ce, t, none())
        assertEquals("WAIT", result.direction)
        assertEquals("PULLBACK", result.reason)
        assertEquals("DOWN", result.diagnostics["measured_direction"])
        assertEquals(EntryPermission.WAIT_DIRECTION_UNSTABLE, EntryPermission.atLock(1, w, true))
    }

    @Test
    fun genuineReversalCanBecomeTheNewSide() {
        val low = 400.0 - 21 * 8.0
        val ce = drive((0 until 22).map { 400.0 - it * 8.0 } + (1..12).map { low + it * 8.0 })
        val t = tOf(ce)
        val w = ce.wave(t)
        assertTrue(w.broken || w.dominant > 0)
        val broken = wave(1, retrace = 0.70, broken = true, phase = "REVERSAL", z20 = -0.8)
        assertEquals("", EntryPermission.publication(1, broken, 8.0, 1.0, 1.0, 0.55, 0.1))
        val result = OpportunityDecider.evaluate(qualified("UP", "FAIL_BRK"), ce, t, none())
        assertEquals(result.reason, "UP", result.direction)
    }

    @Test
    fun shockImpulseIsNotAnEntry() {
        val ce = drive((0 until 20).map { 180.0 } + listOf(260.0, 300.0))
        val t = tOf(ce)
        val w = ce.wave(t)
        assertTrue(w.shock)
        val result = OpportunityDecider.evaluate(qualified("UP"), ce, t, none())
        assertEquals("WAIT", result.direction)
        assertEquals("SHOCK_UNRESOLVED", result.reason)
        assertEquals(EntryPermission.WAIT_SHOCK, EntryPermission.atLock(1, w, true))
    }

    @Test
    fun shockResolutionCanBecomeAValidEntry() {
        val established = (1..14).map { 300.0 + it * 6.0 }
        val ce = drive((0 until 16).map { 180.0 } + listOf(260.0, 300.0) + established)
        val t = tOf(ce)
        val w = ce.wave(t)
        assertTrue(!w.shock)
        assertEquals("UP", w.sideName())
        val result = OpportunityDecider.evaluate(qualified("UP"), ce, t, none())
        assertEquals(result.reason, "UP", result.direction)
        assertEquals("", EntryPermission.atLock(1, w, true))
    }

    @Test
    fun positiveVelocityWithCollapsingAccelerationIsExhausted() {
        val healthy = wave(1, 0.0)
        assertEquals(EntryPermission.WAIT_EXHAUSTED, EntryPermission.publication(1, healthy, 24.0, -10.0, 1.0, 0.80, 0.18))
        assertEquals("", EntryPermission.publication(1, healthy, 8.0, -1.0, 1.0, 0.40, 0.05))
        assertEquals("", EntryPermission.publication(1, healthy, 40.0, 20.0, 1.0, 0.70, 0.10))
        val quiet = wave(1, 0.0, z3 = 0.2)
        assertEquals("", EntryPermission.publication(1, quiet, 1.0, -10.0, 4.0, 0.40, 0.10))
        val fastButTypical = wave(1, 0.0, z3 = 1.05)
        assertEquals(EntryPermission.WAIT_EXHAUSTED, EntryPermission.publication(1, fastButTypical, 24.94, -21.41, 41.33, 0.80, 0.18))
    }

    @Test
    fun negativeVelocityWithCollapsingDownsideMomentumIsExhausted() {
        val healthy = wave(-1, 0.0)
        assertEquals(EntryPermission.WAIT_EXHAUSTED, EntryPermission.publication(-1, healthy, -24.0, 10.0, 1.0, 0.20, 0.18))
        assertEquals("", EntryPermission.publication(-1, healthy, -8.0, 1.0, 1.0, 0.60, 0.05))
        assertEquals("", EntryPermission.publication(-1, healthy, -19.0, -3.5, 1.0, 0.31, 0.0))
    }

    @Test
    fun signalValidAtPublicationCanBeInvalidAtLock() {
        val atPublish = wave(1, 0.12)
        assertEquals("", EntryPermission.publication(1, atPublish, 12.0, 2.0, 1.0, 0.40, 0.05))
        assertEquals("", EntryPermission.atLock(1, atPublish, true))
        val shockLater = wave(-1, 0.0, shock = true, phase = "SHOCK", z3 = -1.9, z6 = -2.3, z10 = -1.9, z20 = -0.7)
        assertEquals(EntryPermission.WAIT_SHOCK, EntryPermission.atLock(-1, shockLater, true))
        val turned = WaveRead(true, 0, "NONE", 0.0, false, false, -1, -0.35, -0.24, -0.41, 0.27)
        assertEquals(EntryPermission.WAIT_PULLBACK, EntryPermission.atLock(1, turned, true))
        assertEquals(EntryPermission.WAIT_STALE_READ, EntryPermission.atLock(1, atPublish, false))
    }

    @Test
    fun signalStillValidAtLockWhenOnlyTheShortWindowDips() {
        val dip = wave(1, 0.15, phase = "PULLBACK", z3 = -0.66, z6 = 1.19, z10 = 0.94, z20 = 2.22)
        assertEquals("", EntryPermission.atLock(1, dip, true))
        val read = EntryPermission.Read(1, dip, true, velocity = -3.8, acceleration = -5.8, stepScale = 1.0, rangePosition = 0.55, exhaustion = 0.0)
        assertEquals("", EntryPermission.atEntry(read))
        val stillRunning = wave(1, 0.08)
        val live = EntryPermission.Read(1, stillRunning, true, 13.0, 2.0, 1.0, 0.40, 0.05)
        assertEquals("", EntryPermission.atEntry(live))
    }

    @Test
    fun tradeClockStaysIndependentOfTheObservationWindow() {
        val cycle = OpportunityCycle()
        cycle.start(0L)
        val lockedAt = 25_000L
        val tradeTarget = lockedAt + 60_000L
        cycle.onUserLocked(lockedAt)
        assertEquals(lockedAt + 90_000L, cycle.resetAtMs)
        assertEquals(60_000L, tradeTarget - lockedAt)
        assertEquals("60", ExperimentTag.PREDICTION_HORIZON_S)
    }

    @Test
    fun continuousOpportunityPublishesInsideTheNinetySecondWindow() {
        val ce = drive((0 until 20).map { 20.0 + it * 6.0 })
        val cycle = OpportunityCycle()
        cycle.start(0L)
        val published = cycle.consider(qualified("UP"), ce, tOf(ce), none())
        assertEquals("UP", published?.direction)
        assertTrue(cycle.resetAtMs == 90_000L)
        assertEquals(EntryPermission.VALID_UP, published?.diagnostics?.get("entry_state"))
    }

    @Test
    fun connectedTipRejectsADetachedRightEdgeFragment() {
        val n = 300
        val path = DoubleArray(n) { 200.0 - 0.2 * it }
        val real = BooleanArray(n)
        for (i in 10..200) real[i] = true
        for (i in 240..259) {
            real[i] = true
            path[i] = 900.0
        }
        assertTrue(TraceGeometry.adoptConnectedTip(path, real))
        assertFalse(real[259])
        assertEquals(200, real.indexOfLast { it })
    }

    @Test
    fun registrationDoesNotTurnAVerticalTranslationIntoAReversal() {
        val ce = ChangeEngine()
        for (i in 0 until 20) {
            val y = DoubleArray(160) { k -> 40.0 + 0.35 * k + i * 15.0 }
            ce.update(i.toDouble(), y)
        }
        val w = ce.wave(19.0)
        assertTrue("z3=${w.z3} phase=${w.phase}", abs(w.z3) < 0.5 || !w.valid)
        assertFalse(w.broken)
    }

    @Test
    fun experienceCannotMakeAnInvalidEntryValid() {
        val ce = drive((0 until 24).map { 40.0 + it * 6.0 })
        val t = tOf(ce)
        val late = qualified("UP", exhaustion = 0.80, rangePosition = 1.20)
        val blocked = OpportunityDecider.evaluate(late, ce, t, none())
        assertEquals("WAIT", blocked.direction)
        assertEquals(EntryPermission.WAIT_BAD_ENTRY_LOCATION, blocked.reason)
        assertEquals("UP", blocked.diagnostics["measured_direction"])
        val stillBlocked = OpportunityDecider.evaluate(late, ce, t, avoid())
        assertEquals("WAIT", stillBlocked.direction)
        assertEquals(EntryPermission.WAIT_BAD_ENTRY_LOCATION, stillBlocked.reason)
        val open = OpportunityDecider.evaluate(qualified("UP"), ce, t, avoid())
        assertEquals("WAIT", open.direction)
        assertEquals("EXPERIENCE_AVOID", open.reason)
        assertEquals("UP", open.diagnostics["measured_direction"])
    }

    @Test
    fun mirroredPathsDoNotPreferASide() {
        val up = drive((0 until 24).map { 40.0 + it * 6.0 })
        val down = drive((0 until 24).map { 400.0 - it * 6.0 })
        val upR = OpportunityDecider.evaluate(qualified("UP"), up, tOf(up), none())
        val downR = OpportunityDecider.evaluate(qualified("DOWN"), down, tOf(down), none())
        assertEquals("UP", upR.direction)
        assertEquals("DOWN", downR.direction)
        assertEquals(upR.reason, downR.reason)
        val upWave = wave(1, 0.0)
        val downWave = wave(-1, 0.0)
        assertEquals(
            EntryPermission.publication(1, upWave, 24.0, -10.0, 1.0, 0.8, 0.2),
            EntryPermission.publication(-1, downWave, -24.0, 10.0, 1.0, 0.2, 0.2)
        )
    }

    @Test
    fun decisionLayerHasNoSessionOrAccountIdentity() {
        val names = listOf(
            "EntryPermission.kt", "OpportunityCycle.kt", "CycleDecider.kt", "ChangeEngine.kt",
            "SignalAnalyzer.kt", "ExperienceStore.kt", "ExperienceMath.kt"
        )
        for (name in names) {
            val src = source(name)
            assertFalse(name, src.contains("CHART_TIMEFRAME"))
            assertFalse(name, src.contains("account_mode"))
            assertFalse(name, src.contains("202610"))
            assertFalse(name, src.contains("TIMEFRAME"))
        }
        val a = EntryPermission.publication(1, wave(1, 0.1), 6.0, 1.0, 1.0, 0.4, 0.1)
        val b = EntryPermission.publication(1, wave(1, 0.1), 6.0, 1.0, 1.0, 0.4, 0.1)
        assertEquals(a, b)
        assertEquals("", a)
    }

    @Test
    fun telemetryTagsAreEnoughForALaterComparison() {
        val one = ExperimentTag.sessionFields("1M", "DEMO")
        val five = ExperimentTag.sessionFields("5M", "REAL")
        assertEquals("1M", one["TIMEFRAME"])
        assertEquals("DEMO", one["account_mode"])
        assertEquals("60", one["prediction_horizon_s"])
        assertEquals("90", one["observation_max_s"])
        assertEquals("5M", five["TIMEFRAME"])
        assertEquals("REAL", five["account_mode"])
        assertEquals("UNSPECIFIED", ExperimentTag.sessionFields("2M", "LIVE")["TIMEFRAME"])
        assertEquals("UNSPECIFIED", ExperimentTag.sessionFields("1M", "broker")["account_mode"])
        assertEquals("DEMO", AccountMode.cycle("UNSPECIFIED"))
        assertEquals("REAL", AccountMode.cycle("DEMO"))
        assertEquals("UNSPECIFIED", AccountMode.cycle("REAL"))
        val readme = ExperimentTag.readme("5M", "REAL")
        assertTrue(readme.contains("TIMEFRAME=5M"))
        assertTrue(readme.contains("account_mode=REAL"))
        assertTrue(readme.contains("prediction_horizon_s=60"))
    }

    @Test
    fun climaxBeyondTheSwingUsesTheExistingExhaustionBar() {
        val healthy = wave(1, 0.0)
        assertEquals(EntryPermission.WAIT_BAD_ENTRY_LOCATION, EntryPermission.publication(1, healthy, 10.0, 2.0, 1.0, 1.10, 0.62))
        assertEquals("", EntryPermission.publication(1, healthy, 10.0, 2.0, 1.0, 0.96, 0.00))
        assertEquals("", EntryPermission.publication(1, healthy, 10.0, 2.0, 1.0, 1.10, 0.50))
        assertEquals("", EntryPermission.publication(1, healthy, 10.0, 2.0, 1.0, -0.15, 0.80))
        val down = wave(-1, 0.0)
        assertEquals(EntryPermission.WAIT_BAD_ENTRY_LOCATION, EntryPermission.publication(-1, down, -10.0, -2.0, 1.0, -0.05, 0.62))
        assertEquals("", EntryPermission.publication(-1, down, -10.0, -2.0, 1.0, 0.10, 0.90))
    }

    private fun source(name: String): String {
        val candidates = listOf(
            File("src/main/java/com/example/livesignalassistant/$name"),
            File("app/src/main/java/com/example/livesignalassistant/$name"),
            File("build_src/app/src/main/java/com/example/livesignalassistant/$name")
        )
        val file = candidates.firstOrNull { it.isFile } ?: error("missing $name in ${File(".").absolutePath}")
        return file.readText()
    }
}

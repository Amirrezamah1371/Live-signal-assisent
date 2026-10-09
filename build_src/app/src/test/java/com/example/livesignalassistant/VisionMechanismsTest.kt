package com.example.livesignalassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sin

/**
 * Mechanisms, not memorized trades.
 * A rule is here only when the same predicate separates a healthy case from the failure it names.
 */
class VisionMechanismsTest {
    private fun none() = object : ExperienceSource {
        override fun advise(action: String, state: String, regime: String, band: String) =
            ExperienceStore.Advice("NONE", 0.0, 0.0, 0.5, 0.0, 1.0, -1, "", "TEST", 0.5)
    }

    private fun obs(tMs: Long, dir: String, reason: String = "TREND"): Obs {
        val direction = if (dir == "UP" || dir == "DOWN") dir else "WAIT"
        val side = if (dir == "DOWN") -1 else 1
        return Obs(tMs, SignalResult(
            direction, 60, 80, entryQuality = 78, conflict = 10, reason = reason,
            diagnostics = mapOf("exhaustion" to 0.05, "trend_regime" to 0.8),
            side = side, traceQuality = 0.95
        ))
    }

    private fun votes(dir: String, n: Int = 50, start: Long = 40000L): List<Obs> =
        (0 until n).map { i -> obs(start + i * 1000L, dir) }

    /** Straight ramp in price-up coordinates. shift scrolls it; scale and bias are a viewport. */
    private fun ramp(shift: Int, scale: Double = 1.0, bias: Double = 0.0, tip: Double = 0.0): DoubleArray {
        val n = 160
        return DoubleArray(n) { i ->
            val y = scale * (20.0 + 0.35 * (i + shift)) + bias
            if (i >= n - 12) y + tip else y
        }
    }

    /** Rise through the left and middle, then either hold the high or give it back. */
    private fun swing(drop: Double): DoubleArray {
        val n = 180
        return DoubleArray(n) { i ->
            val t = i / (n - 1.0)
            if (t < 0.62) 40.0 + 140.0 * (t / 0.62) else 180.0 - drop * ((t - 0.62) / 0.38)
        }
    }

    private fun feed(frames: List<DoubleArray>): ChangeEngine {
        val ce = ChangeEngine()
        frames.forEachIndexed { i, p -> ce.update(i.toDouble(), p) }
        return ce
    }

    @Test
    fun fixedViewportKeepsTheTipDirection() {
        val ce = ChangeEngine()
        ce.update(0.0, ramp(0))
        val up = ce.update(1.0, ramp(2, tip = 25.0))
        assertEquals("OK", up["reg_status"])
        val rose = (ce.debugState()["last"] as Double)
        ce.update(2.0, ramp(4, tip = -30.0))
        val fell = ce.debugState()["last"] as Double
        assertTrue("registered tip should follow a downward tip move", fell < rose)
    }

    @Test
    fun autoscaleDoesNotInvertARealRiseOrARealFall() {
        val ce = ChangeEngine()
        ce.update(0.0, ramp(0))
        val v0 = ce.debugState()["last"] as Double
        val zoomIn = ce.update(1.0, ramp(2, scale = 1.7, bias = 30.0, tip = 20.0))
        assertEquals("OK", zoomIn["reg_status"])
        assertEquals(1.7, zoomIn["reg_a"] as Double, 0.08)
        val v1 = ce.debugState()["last"] as Double
        assertTrue(v1 > v0)
        val zoomOut = ce.update(2.0, ramp(4, scale = 0.7, bias = -15.0, tip = -25.0))
        assertEquals("OK", zoomOut["reg_status"])
        assertTrue((ce.debugState()["last"] as Double) < v1)
    }

    @Test
    fun viewportTranslationDoesNotBecomeAPriceReversal() {
        val ce = ChangeEngine()
        ce.update(0.0, ramp(0))
        val v0 = ce.debugState()["last"] as Double
        // Same shape, scrolled and shifted by a constant. The tip is the scrolled continuation, not a reversal.
        val info = ce.update(1.0, ramp(3, bias = 40.0))
        assertEquals("OK", info["reg_status"])
        val dv = (ce.debugState()["last"] as Double) - v0
        assertTrue("a pan plus a small scroll must not register as a large reversal, dv=$dv", dv > -5.0)
        assertTrue(absScale(ce) < 5.0)
    }

    @Test
    fun noisyFramesDoNotCompoundTheScale() {
        val rng = Random(3)
        val ce = ChangeEngine()
        repeat(100) { t ->
            val p = DoubleArray(180) { i -> 60.0 + 0.15 * i + 12.0 * sin(i / 8.0) + rng.nextGaussian() * 2.5 }
            ce.update(t.toDouble(), p)
        }
        val scale = ce.debugState()["scale_a"] as Double
        assertTrue("fixed viewport scale drifted: $scale", scale in 0.75..1.25)
        assertTrue(scale.isFinite())
        assertTrue("reference became non-finite", (ce.debugState()["last"] as Double).isFinite())
    }

    @Test
    fun tipMoveSignSurvivesFrameNoise() {
        val rng = Random(11)
        fun frame(shift: Int, tipDelta: Double): DoubleArray {
            val n = 180
            val src = DoubleArray(n + shift + 4) { i -> 70.0 + 0.22 * i + 18.0 * sin(i / 6.0) }
            val out = DoubleArray(n) { i -> src[i + shift] + rng.nextGaussian() * 2.0 }
            for (i in n - 10 until n) out[i] += tipDelta
            return out
        }
        val ce = ChangeEngine()
        ce.update(0.0, frame(0, 0.0))
        val before = ce.debugState()["last"] as Double
        val info = ce.update(1.0, frame(2, -35.0))
        assertEquals("OK", info["reg_status"])
        assertTrue(
            "downward tip was registered upward: ${ce.debugState()["last"]} vs $before",
            (ce.debugState()["last"] as Double) < before
        )
    }

    @Test
    fun detachedRightEdgeIsNotTheTip() {
        val real = BooleanArray(200) { it in 10..150 }
        for (i in 190..196) real[i] = true
        TraceGeometry.stripDetached(real)
        assertFalse(real[193])
        assertTrue(real[150])
        val path = DoubleArray(200) { i -> if (i >= 190) 5000.0 else i.toDouble() }
        val motion = TraceGeometry.measure(path, real)
        assertTrue(motion.usable)
        assertTrue("detached speck must not become the tip", motion.net < 400.0)
    }

    @Test
    fun channelsDisagreeWhenRegisteredRiseContradictsTheFrame() {
        val rejected = DoubleArray(180) { i ->
            if (i < 140) 40.0 + i * 0.6 else 124.0 - (i - 140) * 6.0
        }
        val falling = TraceGeometry.measure(rejected, null)
        assertTrue(falling.usable)
        assertTrue("leg ${falling.leg} swing ${falling.swing}", falling.leg < 0.0)
        assertTrue("leg ${falling.leg} swing ${falling.swing}", TraceGeometry.channelsDisagree(2.0, falling))
        val rising = TraceGeometry.measure(swing(0.0), null)
        assertFalse(TraceGeometry.channelsDisagree(2.0, rising))
        assertTrue(
            "a weak registered slope still disagrees with a material opposite leg",
            TraceGeometry.channelsDisagree(0.2, falling)
        )
        assertFalse("the same weak slope agrees with a rising leg", TraceGeometry.channelsDisagree(0.2, rising))
        assertTrue(
            "registered fall must disagree with a material visible rise",
            TraceGeometry.channelsDisagree(-2.0, TraceGeometry.measure(
                DoubleArray(180) { i -> if (i < 140) 160.0 - i * 0.6 else 76.0 + (i - 140) * 6.0 },
                null
            ))
        )
    }

    @Test
    fun failedExtremeWaitsAndAHeldHighDoesNot() {
        assertEquals(
            "FAILED_EXTREME",
            EntrySafety.block(
                agreement = 1.0, state = "CONTINUING", exhaustion = 0.05, impulseZ = 2.0,
                velocityRatio = 1.0, sincePeakSec = 0, counter = 0.0, refuseWeight = 0.0, directWeight = 6.0,
                retraceAgainst = 0.62, legAgainst = true
            )
        )
        assertEquals(
            "",
            EntrySafety.block(
                agreement = 1.0, state = "CONTINUING", exhaustion = 0.05, impulseZ = 2.0,
                velocityRatio = 1.0, sincePeakSec = 0, counter = 0.0, refuseWeight = 0.0, directWeight = 6.0,
                retraceAgainst = 0.0, legAgainst = false
            )
        )
        assertEquals(
            "RAW_LEG_OPPOSES",
            EntrySafety.block(
                agreement = 1.0, state = "CONTINUING", exhaustion = 0.05, impulseZ = 2.0,
                velocityRatio = 1.0, sincePeakSec = 1, counter = 0.1, refuseWeight = 0.0, directWeight = 6.0,
                signedZ3 = 0.2, signedZ6 = 0.1, signedZ10 = 0.4,
                retraceAgainst = 0.2, legAgainst = true, materialLegAgainst = true
            )
        )
        // The registered path is still the rise. The last frame gives the high back.
        // Chasing that old UP is not a publish. Following the new direction, or waiting, both qualify.
        val chased = ChangeEngine()
        repeat(16) { chased.update(it.toDouble(), ramp(it * 2)) }
        chased.update(16.0, ramp(32, tip = -90.0))
        val waited = CycleDecider.decide(votes("UP"), 90000L, chased, 16.0, none(), 0L)
        assertEquals("WAIT", waited.direction)
        assertEquals("AGAINST_RECENT", waited.reason)
        val held = feed((0 until 16).map { swing(0.0) })
        val published = CycleDecider.decide(votes("UP"), 90000L, held, 15.0, none(), 0L)
        assertEquals(published.reason, "UP", published.direction)
    }

    @Test
    fun staleBaseCannotPublishAgainstTheCurrentPath() {
        val rising = feed((0 until 18).map { ramp(it * 2) })
        val downVotes = votes("DOWN")
        val result = CycleDecider.decide(downVotes, 90000L, rising, 17.0, none(), 0L)
        assertNotEquals("stale DOWN votes published against a rising path", "DOWN", result.direction)
        assertEquals("REGIME_CHANGE", result.reason)
    }

    @Test
    fun materialOppositeLegDoesNotPublishTheOldSide() {
        val ce = ChangeEngine()
        repeat(16) { ce.update(it.toDouble(), ramp(it * 2)) }
        val alien = DoubleArray(180) { i ->
            when {
                i < 110 -> if (i % 2 == 0) 2500.0 else -2500.0
                i < 150 -> 40.0 + (i - 110) * 0.5
                else -> 60.0 - (i - 150) * 4.0
            }
        }
        ce.update(16.0, alien)
        val result = CycleDecider.decide(votes("UP"), 90000L, ce, 16.0, none(), 0L)
        assertEquals("WAIT", result.direction)
        assertEquals("VISION_UNRELIABLE", result.reason)
        assertEquals(true, result.diagnostics["channel_disagree"])
    }

    @Test
    fun scarceVotesFollowAConfirmedPathAndWaitWithoutOne() {
        val rising = feed((0 until 18).map { ramp(it * 2) })
        val few = ArrayList<Obs>()
        few += votes("UP", n = 2, start = 40000L)
        repeat(48) { i -> few += obs(42000L + i * 1000L, "WAIT", "FILTER") }
        val published = CycleDecider.decide(few, 90000L, rising, 17.0, none(), 0L)
        assertEquals(published.reason, "UP", published.direction)
        assertEquals(true, published.diagnostics["path_confirmed"])
        val flat = feed((0 until 18).map { DoubleArray(160) { 40.0 } })
        val quiet = ArrayList<Obs>()
        quiet += votes("UP", n = 2, start = 40000L)
        repeat(48) { i ->
            quiet += Obs(42000L + i * 1000L, SignalResult(
                "WAIT", 60, 0, reason = "LOW_VISIBILITY", side = 0, traceQuality = 0.0
            ))
        }
        val waited = CycleDecider.decide(quiet, 90000L, flat, 17.0, none(), 0L)
        assertEquals("WAIT", waited.direction)
        assertEquals("FEW_DIRECTIONAL", waited.reason)
    }

    @Test
    fun confirmedRiseRemainsEligible() {
        val rising = feed((0 until 18).map { ramp(it * 2) })
        val result = CycleDecider.decide(votes("UP"), 90000L, rising, 17.0, none(), 0L)
        assertEquals(result.reason, "UP", result.direction)
        assertEquals(false, result.diagnostics["strength_is_probability"])
        assertEquals(1.0, result.diagnostics["vision_reliability"] as Double, 1e-9)
    }

    @Test
    fun experienceCannotOverrideVisionOrEntrySafety() {
        assertEquals(
            "VISION_UNRELIABLE",
            DecisionGate.block(true, true, "CONTINUING", 0.0, 90.0, 99.0, "NONE", "", visionReliable = false)
        )
        assertEquals(
            "VISION_UNRELIABLE",
            DecisionGate.block(true, true, "CONTINUING", 0.0, 90.0, 99.0, "AVOID", "", visionReliable = false)
        )
        assertEquals(
            "FAILED_EXTREME",
            DecisionGate.block(true, true, "CONTINUING", 0.0, 90.0, 99.0, "NONE", "FAILED_EXTREME")
        )
        assertEquals(
            "STALE_DIRECTION",
            DecisionGate.block(true, true, "CONTINUING", 0.0, 90.0, 99.0, "NONE", "", staleDirection = true)
        )
        assertEquals(
            "RAW_LEG_OPPOSES",
            DecisionGate.block(true, true, "CONTINUING", 0.0, 90.0, 99.0, "NONE", "RAW_LEG_OPPOSES")
        )
        assertEquals(
            "CURRENT_DIRECTION_UNSUPPORTED",
            DecisionGate.block(
                true, true, "CONTINUING", 0.0, 90.0, 99.0, "NONE",
                currentDirectionSupported = false
            )
        )
    }

    @Test
    fun oneCandidateMissDoesNotDiscardTheCandidateTrack() {
        val ce = ChangeEngine()
        repeat(10) { ce.update(it.toDouble(), ramp(it * 2)) }
        val bad = DoubleArray(160) { if (it % 2 == 0) 3000.0 else -3000.0 }
        val other = DoubleArray(160) { if (it % 3 == 0) -2400.0 else 900.0 + it }
        val first = ce.update(10.0, bad)
        assertTrue(first["reg_reason"] as String, (first["reg_reason"] as String).startsWith("HELD_"))
        val kept = ce.debugState()["candidate_points"]
        val second = ce.update(11.0, other)
        assertTrue(second["reg_reason"] as String, (second["reg_reason"] as String).startsWith("HELD_"))
        assertEquals(kept, ce.debugState()["candidate_points"])
        val back = ce.update(12.0, ramp(12 * 2))
        assertEquals("ACCEPTED", back["reg_reason"])
    }

    private fun absScale(ce: ChangeEngine) = kotlin.math.abs(ce.debugState()["scale_a"] as Double)
}

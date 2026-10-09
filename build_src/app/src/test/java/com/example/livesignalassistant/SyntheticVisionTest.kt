package com.example.livesignalassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Synthetic charts. Each case checks a mechanism: raw and registered motion stay consistent,
 * or the disagreement is visible to the vision gate. None of these encode a logged trade.
 */
class SyntheticVisionTest {
    private fun ramp(shift: Int, scale: Double = 1.0, bias: Double = 0.0, tip: Double = 0.0, n: Int = 180): DoubleArray {
        return DoubleArray(n) { i ->
            val y = scale * (30.0 + 0.45 * (i + shift)) + bias
            if (i >= n - 8) y + tip else y
        }
    }

    private fun fall(shift: Int, scale: Double = 1.0, bias: Double = 0.0, tip: Double = 0.0, n: Int = 180): DoubleArray {
        return DoubleArray(n) { i ->
            val y = scale * (220.0 - 0.45 * (i + shift)) + bias
            if (i >= n - 8) y + tip else y
        }
    }

    private fun engine(frames: List<DoubleArray>): ChangeEngine {
        val ce = ChangeEngine()
        frames.forEachIndexed { i, p -> ce.update(i.toDouble(), p) }
        return ce
    }

    private fun last(ce: ChangeEngine) = ce.debugState()["last"] as Double

    private fun scale(ce: ChangeEngine) = ce.debugState()["scale_a"] as Double

    /** Registered move and the frame leg must share a sign, or the channels are marked apart. */
    private fun consistentOrUnsafe(registeredZ3: Double, motion: TraceGeometry.Motion) {
        val apart = TraceGeometry.channelsDisagree(registeredZ3, motion)
        val raw = motion.leg
        if (!apart && motion.usable && abs(registeredZ3) >= 0.5 && abs(raw) > maxOf(4.0 * motion.step, 0.15 * motion.swing)) {
            assertTrue("z3=$registeredZ3 leg=$raw", registeredZ3 * raw > 0.0)
        }
    }

    @Test
    fun fixedViewportRealPriceUp() {
        val ce = ChangeEngine()
        ce.update(0.0, ramp(0))
        val v0 = last(ce)
        val info = ce.update(1.0, ramp(2, tip = 18.0))
        assertEquals("OK", info["reg_status"])
        assertTrue(last(ce) > v0)
        val motion = ce.localMotion()
        assertTrue(motion.leg > 0.0)
        consistentOrUnsafe(2.0, motion)
    }

    @Test
    fun fixedViewportRealPriceDown() {
        val ce = ChangeEngine()
        ce.update(0.0, fall(0))
        val v0 = last(ce)
        val info = ce.update(1.0, fall(2, tip = -18.0))
        assertEquals("OK", info["reg_status"])
        assertTrue(last(ce) < v0)
        val motion = ce.localMotion()
        assertTrue(motion.leg < 0.0)
        consistentOrUnsafe(-2.0, motion)
    }

    @Test
    fun autoscaleWithPriceUpKeepsTheRise() {
        val ce = ChangeEngine()
        ce.update(0.0, ramp(0))
        val v0 = last(ce)
        val info = ce.update(1.0, ramp(2, scale = 1.8, bias = 25.0, tip = 12.0))
        assertEquals("OK", info["reg_status"])
        assertTrue(last(ce) > v0)
        assertTrue(abs(scale(ce)) < 8.0)
        consistentOrUnsafe(1.5, ce.localMotion())
    }

    @Test
    fun autoscaleWithPriceDownKeepsTheFall() {
        val ce = ChangeEngine()
        ce.update(0.0, fall(0))
        val v0 = last(ce)
        val info = ce.update(1.0, fall(2, scale = 0.65, bias = -20.0, tip = -12.0))
        assertEquals("OK", info["reg_status"])
        assertTrue(last(ce) < v0)
        assertTrue(abs(scale(ce)) < 8.0)
        consistentOrUnsafe(-1.5, ce.localMotion())
    }

    @Test
    fun viewportTranslationIsNotAReversal() {
        val ce = ChangeEngine()
        ce.update(0.0, ramp(0))
        val v0 = last(ce)
        val info = ce.update(1.0, ramp(3, bias = 80.0))
        assertEquals("OK", info["reg_status"])
        val dv = last(ce) - v0
        assertTrue("dv=$dv", dv > -8.0)
        assertTrue(abs(scale(ce)) < 5.0)
    }

    @Test
    fun viewportRedrawKeepsTheSameDirection() {
        val ce = ChangeEngine()
        ce.update(0.0, ramp(0))
        val v0 = last(ce)
        val redrawn = ramp(2, tip = 10.0)
        val real = BooleanArray(redrawn.size) { it !in 70..95 }
        val info = ce.update(1.0, redrawn, real)
        assertEquals("OK", info["reg_status"])
        assertTrue(last(ce) > v0)
    }

    @Test
    fun zoomChangeDoesNotFlipTheSign() {
        val ce = ChangeEngine()
        ce.update(0.0, ramp(0))
        val up = ce.update(1.0, ramp(2, scale = 2.2, tip = 8.0))
        assertEquals("OK", up["reg_status"])
        val v1 = last(ce)
        val down = ce.update(2.0, fall(0, scale = 1.4, tip = -8.0))
        // A new shape can fail to match. If it matches, the fall must stay a fall.
        if (down["reg_status"] == "OK") assertTrue(last(ce) < v1)
        assertTrue(scale(ce).isFinite())
        assertTrue(abs(scale(ce)) <= 40.0)
    }

    @Test
    fun temporaryKeypadOverlayDoesNotReverseTheRise() {
        val ce = ChangeEngine()
        repeat(6) { ce.update(it.toDouble(), ramp(it * 2)) }
        val before = last(ce)
        val covered = ramp(12, tip = 10.0)
        val real = BooleanArray(covered.size) { it < covered.size - 55 }
        ce.update(6.0, covered, real)
        val back = ce.update(7.0, ramp(14, tip = 10.0))
        assertTrue(back["reg_reason"] as String, scale(ce).isFinite() && abs(scale(ce)) < 8.0)
        assertTrue("before=$before after=${last(ce)}", last(ce) > before - 5.0)
    }

    @Test
    fun accountSheetOverlayLeavesTheTipTrace() {
        val ce = ChangeEngine()
        ce.update(0.0, ramp(0))
        val sheet = ramp(2, tip = 8.0)
        val real = BooleanArray(sheet.size) { it < 30 || it > 110 }
        val info = ce.update(1.0, sheet, real)
        assertEquals("OK", info["reg_status"])
        val motion = TraceGeometry.measure(sheet, real)
        assertTrue(motion.usable)
        assertTrue(motion.leg > 0.0)
    }

    @Test
    fun depositBannerIsADetachedMark() {
        val real = BooleanArray(200) { it in 8..150 }
        for (i in 188..194) real[i] = true
        TraceGeometry.stripDetached(real)
        assertFalse(real[190])
        assertTrue(real[150])
        val path = DoubleArray(200) { i -> if (i >= 188) 4000.0 else 20.0 + i }
        val motion = TraceGeometry.measure(path, real)
        assertTrue(motion.usable)
        assertTrue(motion.net < 500.0)
    }

    @Test
    fun missingTipIgnoresPaddingBeyondTheLastRealColumn() {
        val path = ramp(0, tip = 0.0)
        for (i in path.size - 20 until path.size) path[i] = 9000.0
        val real = BooleanArray(path.size) { it < path.size - 20 }
        val motion = TraceGeometry.measure(path, real)
        assertTrue(motion.usable)
        assertTrue("padding became the tip, net=${motion.net}", motion.net < 400.0)
    }

    @Test
    fun wrongTraceCandidateIsNotTheTip() {
        val real = BooleanArray(180) { it in 5..140 }
        for (i in 165..170) real[i] = true
        val path = DoubleArray(180) { i -> if (i >= 165) -2000.0 else 15.0 + 0.5 * i }
        val motion = TraceGeometry.measure(path, real)
        assertTrue(motion.usable)
        assertTrue(motion.net > 0.0)
        assertFalse(TraceGeometry.channelsDisagree(1.2, motion))
    }

    @Test
    fun horizontalLineContaminationDoesNotInventASlope() {
        val flat = DoubleArray(180) { 80.0 }
        val motion = TraceGeometry.measure(flat, null)
        assertTrue(motion.usable)
        assertEquals(0.0, motion.leg, 1e-6)
        assertFalse(TraceGeometry.channelsDisagree(2.0, motion))
        val real = BooleanArray(180) { it in 10..130 }
        for (i in 160..166) real[i] = true
        val path = DoubleArray(180) { i -> if (i in 160..166) 80.0 else 10.0 + i }
        TraceGeometry.stripDetached(real)
        assertFalse(real[163])
    }

    @Test
    fun spikeThenReversalIsAFailedExtreme() {
        val path = DoubleArray(180) { i ->
            if (i < 150) 20.0 + i * 0.8 else 140.0 - (i - 150) * 4.0
        }
        val motion = TraceGeometry.measure(path, null)
        assertTrue(motion.retraceHigh >= 0.5)
        assertTrue(motion.leg < 0.0)
        assertEquals(
            "FAILED_EXTREME",
            EntrySafety.block(
                1.0, "CONTINUING", 0.1, 1.0, 1.0, 0, 0.0, 0.0, 4.0,
                retraceAgainst = motion.retraceHigh, legAgainst = true
            )
        )
        assertTrue(TraceGeometry.channelsDisagree(2.0, motion))
    }

    @Test
    fun vReversalLegFollowsTheRecovery() {
        val path = DoubleArray(180) { i ->
            if (i < 100) 200.0 - i * 1.2 else 80.0 + (i - 100) * 1.1
        }
        val motion = TraceGeometry.measure(path, null)
        assertTrue("leg=${motion.leg}", motion.leg > 0.0)
        assertTrue("retraceHigh=${motion.retraceHigh}", motion.retraceHigh < 0.5)
        assertFalse(TraceGeometry.channelsDisagree(1.5, motion))
    }

    @Test
    fun doubleTopRejectionIsUnsafeForTheOldUp() {
        val path = DoubleArray(180) { i ->
            when {
                i < 70 -> 30.0 + i * 1.4
                i < 100 -> 128.0 - (i - 70) * 1.2
                i < 140 -> 92.0 + (i - 100) * 0.9
                else -> 128.0 - (i - 140) * 2.2
            }
        }
        val motion = TraceGeometry.measure(path, null)
        assertTrue("retrace=${motion.retraceHigh}", motion.retraceHigh >= 0.5)
        assertTrue(motion.leg < 0.0)
        assertEquals(
            "FAILED_EXTREME",
            EntrySafety.block(
                1.0, "CONTINUING", 0.1, 1.2, 0.9, 2, 0.2, 0.0, 5.0,
                retraceAgainst = motion.retraceHigh, legAgainst = motion.leg < 0.0
            )
        )
    }

    @Test
    fun doubleBottomRejectionIsUnsafeForTheOldDown() {
        val path = DoubleArray(180) { i ->
            when {
                i < 70 -> 180.0 - i * 1.4
                i < 100 -> 82.0 + (i - 70) * 1.1
                i < 140 -> 115.0 - (i - 100) * 0.8
                else -> 83.0 + (i - 140) * 2.0
            }
        }
        val motion = TraceGeometry.measure(path, null)
        assertTrue("retraceLow=${motion.retraceLow}", motion.retraceLow >= 0.5)
        assertTrue(motion.leg > 0.0)
        assertEquals(
            "FAILED_EXTREME",
            EntrySafety.block(
                1.0, "CONTINUING", 0.1, 1.2, 0.9, 2, 0.2, 0.0, 5.0,
                retraceAgainst = motion.retraceLow, legAgainst = true
            )
        )
    }

    @Test
    fun breakoutContinuationStaysEligible() {
        val path = DoubleArray(180) { i -> 20.0 + i * 0.7 }
        val motion = TraceGeometry.measure(path, null)
        assertTrue(motion.leg >= 0.0)
        assertTrue(motion.retraceHigh < 0.5)
        assertEquals(
            "",
            EntrySafety.block(
                1.0, "CONTINUING", 0.05, 1.6, 1.0, 0, 0.0, 0.0, 6.0,
                retraceAgainst = motion.retraceHigh, legAgainst = false
            )
        )
        val ce = engine((0 until 16).map { ramp(it * 2) })
        val features = ce.features(15.0, 1)
        assertTrue(features.valid)
        assertEquals(features.state, "CONTINUING", features.state)
    }

    @Test
    fun failedBreakoutBecomesUnsafeWhenPriceReturns() {
        val path = DoubleArray(180) { i ->
            if (i < 150) 25.0 + i * 0.55 else 107.5 + 18.0 - (i - 150) * 3.5
        }
        val motion = TraceGeometry.measure(path, null)
        assertTrue(motion.retraceHigh >= 0.5)
        assertTrue(motion.leg < 0.0)
        assertTrue(TraceGeometry.channelsDisagree(1.6, motion))
    }

    @Test
    fun breakdownContinuationStaysEligible() {
        val path = DoubleArray(180) { i -> 220.0 - i * 0.7 }
        val motion = TraceGeometry.measure(path, null)
        assertTrue(motion.leg <= 0.0)
        assertTrue(motion.retraceLow < 0.5)
        assertEquals(
            "",
            EntrySafety.block(
                1.0, "CONTINUING", 0.05, 1.6, 1.0, 0, 0.0, 0.0, 6.0,
                retraceAgainst = motion.retraceLow, legAgainst = false
            )
        )
        consistentOrUnsafe(-1.8, motion)
    }

    @Test
    fun failedBreakdownBecomesUnsafeWhenPriceReturns() {
        val path = DoubleArray(180) { i ->
            if (i < 150) 200.0 - i * 0.5 else 125.0 - 16.0 + (i - 150) * 3.2
        }
        val motion = TraceGeometry.measure(path, null)
        assertTrue("retraceLow=${motion.retraceLow} leg=${motion.leg}", motion.retraceLow >= 0.5)
        assertTrue(motion.leg > 0.0)
        assertTrue(TraceGeometry.channelsDisagree(-1.6, motion))
    }
}

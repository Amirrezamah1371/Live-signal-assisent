package com.example.livesignalassistant

import kotlin.math.abs
import kotlin.math.max

/**
 * Shape of one extracted trace, measured inside that frame.
 *
 * A positive price scale and a pan preserve order and ratios of differences inside one frame.
 * Those ratios stay meaningful when the cross-frame affine scale does not. Callers compare them
 * with the registered series instead of treating the registered series as the only motion.
 */
object TraceGeometry {
    data class Motion(
        val usable: Boolean,
        val net: Double = 0.0,
        val retraceHigh: Double = 0.0,
        val retraceLow: Double = 0.0,
        val columnsSinceHigh: Int = 0,
        val columnsSinceLow: Int = 0,
        val leg: Double = 0.0,
        val step: Double = 1.0,
        val swing: Double = 0.0
    )

    /**
     * A short run of real columns sitting past a long hole is not the price tip.
     * It is a detached right-edge mark (a HUD fragment or a bad candidate). The main trace
     * keeps its own tip. Short interior holes are left alone.
     */
    fun stripDetached(real: BooleanArray) {
        var guard = 0
        while (guard++ < 3) {
            val hi = real.indexOfLast { it }
            if (hi < 0) return
            var runStart = hi
            while (runStart > 0 && real[runStart - 1]) runStart--
            val runLen = hi - runStart + 1
            var k = runStart - 1
            var gap = 0
            while (k >= 0 && !real[k]) {
                gap++
                k--
            }
            val gapNeed = max(12, real.size / 20)
            if (k >= 0 && runLen <= 8 && gap >= gapNeed) {
                for (i in runStart..hi) real[i] = false
                continue
            }
            return
        }
    }

    /** path is price-up positive. real == null means every column was seen. */
    fun measure(path: DoubleArray?, realIn: BooleanArray?): Motion {
        if (path == null || path.size < 24) return Motion(false)
        val real = BooleanArray(path.size) { i -> realIn?.getOrNull(i) ?: true }
        if (realIn != null) stripDetached(real)
        val hi = real.indexOfLast { it }
        if (hi < 23) return Motion(false)
        val window = max(24, (hi + 1) / 3)
        val from = max(0, hi - window + 1)
        var peak = Double.NEGATIVE_INFINITY
        var trough = Double.POSITIVE_INFINITY
        var ip = from
        var it = from
        var seen = 0
        for (i in from..hi) {
            if (!real[i]) continue
            seen++
            val v = path[i]
            if (v >= peak) {
                peak = v
                ip = i
            }
            if (v <= trough) {
                trough = v
                it = i
            }
        }
        if (seen < 16 || peak == Double.NEGATIVE_INFINITY) return Motion(false)
        var anchor = from
        while (anchor < hi && !real[anchor]) anchor++
        val tip = path[hi]
        val swing = max(peak - trough, 1e-9)
        val swingPx = max(peak - trough, 0.0)
        val steps = ArrayList<Double>(seen)
        var prev = -1
        for (i in from..hi) {
            if (!real[i]) continue
            if (prev >= 0) steps.add(abs(path[i] - path[prev]))
            prev = i
        }
        val step = if (steps.isEmpty()) 1.0 else {
            val sorted = steps.sorted()
            sorted[sorted.size / 2].coerceAtLeast(1e-6)
        }
        // Median of the columns just behind the tip, so one bad pick cannot invent a huge leg.
        val behind = ArrayList<Double>(12)
        var j = hi - 1
        while (j >= from && behind.size < 12) {
            if (real[j]) behind.add(path[j])
            j--
        }
        val leg = if (behind.size >= 4) {
            val sorted = behind.sorted()
            tip - sorted[sorted.size / 2]
        } else 0.0
        return Motion(
            usable = true,
            net = tip - path[anchor],
            retraceHigh = ((peak - tip) / swing).coerceIn(0.0, 1.0),
            retraceLow = ((tip - trough) / swing).coerceIn(0.0, 1.0),
            columnsSinceHigh = hi - ip,
            columnsSinceLow = hi - it,
            leg = leg,
            step = step,
            swing = swingPx
        )
    }

    /**
     * Registered recent slope and the current frame's own net move.
     * 0.50 is the same pullback bar the kinematics already use. The local bar is four
     * typical column steps, with a floor of a few pixels so a flat trace cannot disagree.
     */
    fun channelsDisagree(registeredZ3: Double, motion: Motion): Boolean {
        if (!motion.usable || !registeredZ3.isFinite()) return false
        // The leg is the last few real columns, about the same horizon as the 3-second slope.
        // The longer in-frame net is a different timescale and is not a registration failure.
        // A few pixels of counter-tick is not a broken registration. The leg has to be a
        // real fraction of this frame's own recent swing, or several typical column steps.
        // A weak registered slope does not get a free pass: a material opposite leg means
        // the frame moved and the 3-second series did not follow it.
        val bar = max(4.0 * motion.step, 0.15 * motion.swing)
        if (abs(motion.leg) <= bar) return false
        return registeredZ3 * motion.leg < 0.0
    }
}

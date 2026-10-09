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

    /**
     * The adopted tip has to belong to the dominant connected price path.
     *
     * A short missed patch is still that path, including a steep move. A later piece is kept when
     * it sits on the same vertical neighborhood as the path it would extend. A long right-edge
     * island that is only a small piece of the trace is removed. Two large pieces that do not
     * reconnect are left untouched and reported as not confident, so the caller does not invent a tip.
     *
     * Returns true when the surviving right edge is a confident price tip. `real` is cleared only
     * for a piece that is clearly not that tip.
     */
    fun adoptConnectedTip(path: DoubleArray, real: BooleanArray): Boolean {
        if (path.size != real.size || path.isEmpty()) return false
        if (real.none { it }) return false
        for (i in path.indices) if (real[i] && !path[i].isFinite()) return false
        stripDetached(real)
        if (real.none { it }) return false
        var guard = 0
        while (guard++ < 4) {
            when (val edge = rightEdge(path, real)) {
                is RightEdge.Connected -> return true
                is RightEdge.Island -> {
                    for (i in edge.from..edge.to) real[i] = false
                    // The short run that was sitting behind that fragment is now the right edge.
                    // Judge it with the same speck rule. Leaving it for a later pass makes the
                    // analyzer tip and the registered tip different objects.
                    stripDetached(real)
                    if (real.none { it }) return false
                }
                is RightEdge.Ambiguous -> return false
            }
        }
        return real.any { it }
    }

    private sealed class RightEdge {
        data object Connected : RightEdge()
        data object Ambiguous : RightEdge()
        class Island(val from: Int, val to: Int) : RightEdge()
    }

    private fun rightEdge(path: DoubleArray, real: BooleanArray): RightEdge {
        val idxs = ArrayList<Int>()
        for (i in real.indices) if (real[i]) idxs.add(i)
        if (idxs.size < 2) return RightEdge.Connected
        val gaps = ArrayList<Int>(idxs.size)
        for (i in 1 until idxs.size) gaps.add(idxs[i] - idxs[i - 1] - 1)
        val sorted = gaps.sorted()
        val body = if (sorted.size >= 8) sorted.subList(0, sorted.size - 1) else sorted
        val p95 = if (body.isEmpty()) 0 else body[((body.size - 1) * 95) / 100]
        // Typical holes of this trace, widened so one missed patch is not a new object.
        val bridge = max(4, p95 * 3 + 2)
        // A break has to be wider than both a short missed patch and this trace's own spacing.
        // Length/20 matches the scale already used for a detached speck, with a floor so a
        // handful of empty columns on a short trace is still one stroke.
        val detach = max(max(18, real.size / 20), bridge * 3)
        var at = idxs.lastIndex
        while (at > 0 && idxs[at] - idxs[at - 1] - 1 <= detach) at--
        if (at == 0) return RightEdge.Connected
        val prev = idxs[at - 1]
        val start = idxs[at]
        val rightFrom = start
        val rightTo = idxs.last()
        var rightCount = 0
        var lo = path[start]
        var hiY = path[start]
        for (j in at until idxs.size) {
            rightCount++
            val y = path[idxs[j]]
            if (y < lo) lo = y
            if (y > hiY) hiY = y
        }
        val span = hiY - lo
        val steps = ArrayList<Double>()
        for (i in 1 until at) {
            val a = idxs[i - 1]
            val b = idxs[i]
            if (b - a - 1 <= 1) steps.add(abs(path[b] - path[a]))
        }
        val tail = if (steps.size > 40) steps.subList(steps.size - 40, steps.size) else steps
        val local = if (tail.isEmpty()) 0.0 else tail.sorted()[tail.size / 2]
        val jump = abs(path[start] - path[prev])
        // Budget does not grow with the empty gap. A long flight cannot turn a distant island
        // into a continuation just because recent ticks were steep. The right piece may be as
        // tall as its own span plus a few local steps, which is how a sharp but sampled move looks.
        val allowance = max(local * 8.0, span + local * 4.0)
        if (jump <= allowance) return RightEdge.Connected
        val leftCount = at
        val island = span * 3.0 < jump
        val fragment = rightCount * 4 < leftCount
        if (fragment && island) return RightEdge.Island(rightFrom, rightTo)
        return RightEdge.Ambiguous
    }

    /** path is price-up positive. real == null means every column was seen. */
    fun measure(path: DoubleArray?, realIn: BooleanArray?): Motion {
        if (path == null || path.size < 24) return Motion(false)
        val real = BooleanArray(path.size) { i -> realIn?.getOrNull(i) ?: true }
        if (realIn != null && !adoptConnectedTip(path, real)) return Motion(false)
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

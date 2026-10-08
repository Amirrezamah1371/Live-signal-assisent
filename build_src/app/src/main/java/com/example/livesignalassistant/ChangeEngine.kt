package com.example.livesignalassistant

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Real recent-change features. Values are z-scores (displacement / (step scale * sqrt(window))). */
class ChangeFeatures(
    val valid: Boolean,
    val z3: Double = 0.0, val z6: Double = 0.0, val z10: Double = 0.0, val z20: Double = 0.0,
    val velocity: Double = 0.0, val acceleration: Double = 0.0, val jerk: Double = 0.0,
    val counter: Double = 0.0, val sincePeakSec: Int = 0, val persistenceDecay: Double = 0.0,
    val flips: Int = 0, val agreement: Double = 0.0, val velocityRatio: Double = 0.0,
    val against8: Int = 0, val impulseZ: Double = 0.0, val efficiency: Double = 0.0,
    val state: String = "INSUFFICIENT", val stepScale: Double = 0.0, val points: Int = 0,
    val spanSec: Double = 0.0
)

class SeriesStatus(val points: Int, val spanSec: Double, val reason: String)

/**
 * Cross-frame price series that survives chart auto-scaling.
 * Each new frame's path is registered against the previous frame's path (horizontal scroll shift +
 * affine y mapping a*y+b). The mapping is chained into a single reference scale, so a chart rescale
 * is NOT read as market movement. If registration fails (or frames are >3.5 s apart, e.g. Android
 * paused the app) the series restarts instead of inventing continuity.
 */
class ChangeEngine {
    private var prevT = -1.0
    private var prevP: DoubleArray? = null
    private var scaleA = 1.0
    private var scaleB = 0.0
    private val ts = ArrayList<Double>()
    private val vs = ArrayList<Double>()
    var lastReg = "NEW"
        private set
    private val regLog = ArrayList<Pair<Double, String>>()

    @Synchronized
    fun reset() {
        prevT = -1.0; prevP = null; scaleA = 1.0; scaleB = 0.0; ts.clear(); vs.clear(); lastReg = "NEW"; regLog.clear()
    }

    /** returns [score, shift, a, b, aRaw, overlapLen, relativeResidual] or null (indexes 0..3 drive the decision; 4..6 are diagnostics only) */
    private fun register(old: DoubleArray, nw: DoubleArray): DoubleArray? {
        val n0 = old.size
        val maxShift = max(3, n0 / 8)
        var best: DoubleArray? = null
        for (s in 0..maxShift) {
            val len = min(n0 - s, nw.size) - 3
            if (len < max(30, (0.5 * n0).toInt())) continue
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
            val res = sqrt(sse / len) / (sqrt(vy) + 1e-9)
            val sc = res + (if (abs(aRaw - a) > 1e-9) 1.0 else 0.0)
            if (best == null || sc < best[0]) best = doubleArrayOf(sc, s.toDouble(), a, b, aRaw, len.toDouble(), res)
        }
        return best
    }

    /** p: path with price-up positive (pixel units). t: monotonic seconds. */
    @Synchronized
    fun update(t: Double, p: DoubleArray): Map<String, Any?> {
        var reg = "NEW"; var shift = 0.0; var aUsed = 1.0
        var reason = "NO_PREVIOUS_FRAME"
        var dScore = -1.0; var dShift = -1.0; var dARaw = 0.0; var dB = 0.0; var dLen = 0; var dRes = -1.0; var dClamped = false
        val pp = prevP
        val prevTBefore = prevT
        val oldN = pp?.size ?: 0
        if (pp != null && t - prevT <= 3.5 && t > prevT) {
            val r = register(pp, p)
            if (r != null) {
                dScore = r[0]; dShift = r[1]; dARaw = r[4]; dB = r[3]; dLen = r[5].toInt(); dRes = r[6]; dClamped = abs(r[4] - r[2]) > 1e-9
            }
            if (r != null && r[0] < 0.30) {
                shift = r[1]; aUsed = r[2]; reg = "OK"; reason = "ACCEPTED"
                scaleB = scaleB - scaleA * r[3] / r[2]
                scaleA = scaleA / r[2]
            } else {
                reg = "FAIL"; ts.clear(); vs.clear(); scaleA = 1.0; scaleB = 0.0
                reason = if (r == null) "NO_VALID_SHIFT" else if (abs(r[4] - r[2]) > 1e-9) "SCORE_ABOVE_0.30_SCALE_CLAMPED" else "SCORE_ABOVE_0.30"
            }
        } else if (pp != null) {
            reg = "GAP"; ts.clear(); vs.clear(); scaleA = 1.0; scaleB = 0.0
            reason = if (t <= prevT) "TIME_NOT_ADVANCING" else "FRAME_GAP_OVER_3.5S"
        }
        prevP = p; prevT = t; lastReg = reg
        regLog.add(Pair(t, reg))
        while (regLog.isNotEmpty() && t - regLog[0].first > 45.0) regLog.removeAt(0)
        val tail = p.copyOfRange(max(0, p.size - 3), p.size).sorted()
        val med = tail[tail.size / 2]
        val v = scaleA * med + scaleB
        ts.add(t); vs.add(v)
        while (ts.isNotEmpty() && t - ts[0] > 45.0) { ts.removeAt(0); vs.removeAt(0) }
        return mapOf(
            "reg_status" to reg, "reg_shift" to shift, "reg_a" to aUsed, "ref_v" to v, "ref_points" to ts.size,
            "reg_reason" to reason, "reg_score" to dScore, "reg_best_shift" to dShift, "reg_a_raw" to dARaw,
            "reg_a_clamped" to dClamped,
            "reg_b" to dB, "reg_overlap_len" to dLen, "reg_resid_rel" to dRes,
            "reg_old_n" to oldN, "reg_new_n" to p.size, "reg_t" to t, "reg_prev_t" to prevTBefore
        )
    }

    /** Honest series state for logging: real point count/span and why Recent Features are (in)valid. Does not influence decisions. */
    @Synchronized
    fun seriesStatus(tNow: Double): SeriesStatus {
        val n = ts.size
        if (n == 0) return SeriesStatus(0, 0.0, "EMPTY")
        val span = tNow - ts[0]
        val reason = if (n < 8 && span < 12.0) "TOO_FEW_AND_TOO_SHORT" else if (n < 8) "TOO_FEW_POINTS" else if (span < 12.0) "SPAN_TOO_SHORT" else "OK"
        return SeriesStatus(n, span, reason)
    }

    /** [ok, fail, gap, new] registration counts in the last windowSec seconds (logging only). */
    @Synchronized
    fun regHistory(tNow: Double, windowSec: Double): IntArray {
        val c = IntArray(4)
        for (e in regLog) if (tNow - e.first <= windowSec) {
            when (e.second) { "OK" -> c[0]++; "FAIL" -> c[1]++; "GAP" -> c[2]++; else -> c[3]++ }
        }
        return c
    }

    private fun grid(tNow: Double): DoubleArray? {
        if (ts.size < 8 || tNow - ts[0] < 12.0) return null
        val w = min(30.0, tNow - ts[0]).toInt()
        val g = DoubleArray(w + 1)
        var seg = 0
        for (i in 0..w) {
            val tg = tNow - (w - i).toDouble()
            while (seg < ts.size - 2 && ts[seg + 1] < tg) seg++
            val t0 = ts[seg]; val t1 = ts[seg + 1]
            val f = if (t1 > t0) ((tg - t0) / (t1 - t0)).coerceIn(0.0, 1.0) else 0.0
            g[i] = vs[seg] + (vs[seg + 1] - vs[seg]) * f
        }
        return g
    }

    /** Velocity and acceleration in CURRENT pixel units (for the pixel-space direction brain). */
    @Synchronized
    fun kinematicsPx(tNow: Double): Pair<Double, Double>? {
        val g = grid(tNow) ?: return null
        val n = g.size
        if (n < 8) return null
        val v3 = (g[n - 1] - g[n - 4]) / 3.0
        val v3p = (g[n - 4] - g[n - 7]) / 3.0
        val a = if (scaleA != 0.0) scaleA else 1.0
        return Pair(v3 / a, ((v3 - v3p) / 3.0) / a)
    }

    @Synchronized
    fun features(tNow: Double, sb: Int): ChangeFeatures {
        val g = grid(tNow) ?: return ChangeFeatures(false)
        val w = g.size - 1
        if (w < 12) return ChangeFeatures(false)
        val steps = DoubleArray(w) { g[it + 1] - g[it] }
        val med = steps.sorted()[steps.size / 2]
        val mad = steps.map { abs(it - med) }.sorted()[steps.size / 2] * 1.4826
        val meanAbs = steps.map { abs(it) }.average()
        val u = max(max(mad, 0.6 * meanAbs), 1e-6)
        fun z(win: Int): Double {
            val ww = min(win, w)
            return (g[w] - g[w - ww]) / (u * sqrt(ww.toDouble()))
        }
        val z3 = z(3); val z6 = z(6); val z10 = z(10); val z20 = z(20)
        val v3 = (g[w] - g[w - 3]) / 3.0
        val v3p = (g[w - 3] - g[w - 6]) / 3.0
        val v3pp = if (w >= 9) (g[w - 6] - g[w - 9]) / 3.0 else v3p
        val acc = (v3 - v3p) / 3.0
        val accp = (v3p - v3pp) / 3.0
        val jerk = (acc - accp) / 3.0
        val s = if (sb == 0) 1 else sb
        val x = DoubleArray(w + 1) { s * g[it] }
        var ip = 0
        for (i in 0..w) if (x[i] > x[ip]) ip = i
        var isr = 0
        for (i in 0..ip) if (x[i] < x[isr]) isr = i
        val imp = max(x[ip] - x[isr], 1e-9)
        val counter = (x[ip] - x[w]) / imp
        val sincePeak = w - ip
        val sg = IntArray(w) { if (abs(steps[it]) > 0.25 * u) (if (steps[it] > 0) 1 else -1) else 0 }
        val agreeSteps = IntArray(w) { s * sg[it] }
        val lastN = min(8, w)
        var against = 0
        for (i in w - lastN until w) if (agreeSteps[i] < 0) against++
        fun share(from: Int, to: Int): Double {
            val a = max(0, from); val b = min(w, to)
            if (b <= a) return 0.0
            var c = 0
            for (i in a until b) if (agreeSteps[i] > 0) c++
            return c.toDouble() / (b - a)
        }
        val persRecent = share(w - 5, w)
        val persPrior = if (w >= 10) share(w - 15, w - 5) else persRecent
        val decay = max(0.0, persPrior - persRecent)
        var flips = 0
        var lastNz = 0
        for (i in max(0, w - 12) until w) {
            if (sg[i] != 0) {
                if (lastNz != 0 && sg[i] != lastNz) flips++
                lastNz = sg[i]
            }
        }
        val agree = (Math.signum(s * z3) + Math.signum(s * z6) + Math.signum(s * z10) + Math.signum(s * z20)) / 4.0
        var pv = abs(v3)
        if (w >= 6) {
            pv = 0.0
            for (i in 3..w) pv = max(pv, abs(g[i] - g[i - 3]) / 3.0)
        }
        val velRatio = abs(v3) / max(pv, 1e-9)
        val impz = imp / (u * sqrt(max(1, ip - isr).toDouble()))
        var path = 0.0
        for (i in isr + 1..ip) path += abs(x[i] - x[i - 1])
        val er = if (ip - isr >= 2) (x[ip] - x[isr]) / max(path, 1e-9) else 0.0
        val state = when {
            impz >= 1.5 && er >= 0.5 && velRatio < 0.35 && abs(z6) < 0.8 && sincePeak <= 3 && counter < 0.5 -> "EXHAUSTION"
            flips >= 6 && abs(z10) < 1.0 -> "NOISE"
            s * z10 <= -1.0 && s * z6 <= -0.8 && against >= 5 && counter >= 0.5 && sincePeak >= 5 && !(s * z3 >= 1.0) -> "REGIME_CHANGE"
            s * z6 <= -0.5 -> "PULLBACK"
            else -> "CONTINUING"
        }
        return ChangeFeatures(
            true, z3, z6, z10, z20, v3, acc, jerk, counter, sincePeak, decay, flips, agree, velRatio,
            against, impz, er, state, u, ts.size, tNow - ts[0]
        )
    }
}

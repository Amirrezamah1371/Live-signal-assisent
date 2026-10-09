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
 * The local wave, read from the registered series.
 * A short counter-move stays a pullback until it breaks that wave.
 * [dominant] is +1 for UP, -1 for DOWN, 0 when no wave is established.
 */
class WaveRead(
    val valid: Boolean,
    val dominant: Int = 0,
    val phase: String = "NONE",
    val retrace: Double = 0.0,
    val broken: Boolean = false,
    val shock: Boolean = false,
    val impulseSign: Int = 0,
    val z3: Double = 0.0,
    val z6: Double = 0.0,
    val z10: Double = 0.0,
    val z20: Double = 0.0
) {
    fun sideName(s: Int = dominant): String = when (s) {
        1 -> "UP"
        -1 -> "DOWN"
        else -> "NONE"
    }
}

/**
 * Publication rule for a proposed side. Empty means the wave does not forbid it.
 * This is structure, not a timer: a pullback inside the prior swing cannot become the new trend.
 */
object MarketStructure {
    fun block(sign: Int, wave: WaveRead): String {
        if (!wave.valid || sign == 0) return ""
        // A shock is not a direction yet, on either side. The first seconds of the impulse
        // have not shown whether the prior wave failed.
        if (wave.shock) return "SHOCK_UNRESOLVED"
        // Opposite side, and the prior swing has not been broken.
        if (wave.dominant != 0 && sign != wave.dominant && !wave.broken) return "PULLBACK"
        // The medium windows disagree, or a short impulse never became a 6s/10s or 20s wave.
        // A flat series is not this case: there is no impulse to mistake for a direction.
        if (wave.phase == "UNSTABLE") return "STRUCTURE_UNSTABLE"
        if (wave.dominant == 0 && (abs(wave.z3) >= 0.5 || abs(wave.z6) >= 0.5)) return "STRUCTURE_UNSTABLE"
        return ""
    }
}

/**
 * Cross-frame price series that survives chart auto-scaling.
 *
 * Trusted history and a candidate registration are separate. A frame is accepted into the trusted
 * series only when it aligns to the last trusted polyline (score still < 0.30). A rejected frame
 * does not become the new origin and does not delete trusted points. Repeated rejects are accumulated
 * on a candidate track. The candidate replaces trusted history only after it has itself produced a
 * non-provisional series of at least 8 points spanning 12 seconds. A hole longer than 3.5 s is not
 * bridged with an invented path. Features stay invalid while the series is provisional, too short,
 * or its newest real point is more than 2.5 s old. Nothing is filled flat across a missing frame.
 */
class ChangeEngine {
    private var prevT = -1.0
    private var prevP: DoubleArray? = null
    private var prevReal: BooleanArray? = null
    private var scaleA = 1.0
    private var scaleB = 0.0
    private val ts = ArrayList<Double>()
    private val vs = ArrayList<Double>()
    private var failStreak = 0
    private var provisional = true
    private var lastOkT = -1.0
    var lastReg = "NEW"
        private set
    private val regLog = ArrayList<Pair<Double, String>>()

    private var candP: DoubleArray? = null
    private var candReal: BooleanArray? = null
    private var candT = -1.0
    private var candA = 1.0
    private var candB = 0.0
    private val candTs = ArrayList<Double>()
    private val candVs = ArrayList<Double>()
    private var candProv = true
    private var candMisses = 0
    private var seenPath: DoubleArray? = null
    private var seenReal: BooleanArray? = null

    @Synchronized
    fun reset() {
        prevT = -1.0; prevP = null; prevReal = null; scaleA = 1.0; scaleB = 0.0
        ts.clear(); vs.clear(); lastReg = "NEW"; regLog.clear()
        failStreak = 0; provisional = true; lastOkT = -1.0
        clearCandidate()
        seenPath = null; seenReal = null
    }

    @Synchronized fun pointCount(): Int = ts.size

    @Synchronized fun debugState(): Map<String, Any> = mapOf(
        "points" to ts.size,
        "last" to (vs.lastOrNull() ?: Double.NaN),
        "provisional" to provisional,
        "fail_streak" to failStreak,
        "last_ok_t" to lastOkT,
        "candidate_points" to candTs.size,
        "scale_a" to scaleA
    )

    /**
     * returns [score, shift, a, b, aRaw, overlapLen, relativeResidual] or null.
     * Indexes 0..3 drive the decision; 4..6 are diagnostics only.
     */
    private fun register(old: DoubleArray, nw: DoubleArray, oldReal: BooleanArray?, newReal: BooleanArray?): DoubleArray? {
        val n0 = old.size
        val maxShift = max(3, n0 / 8)
        val tail = max(6, nw.size / 10)
        val need = max(24, (0.40 * min(n0, nw.size)).toInt())
        var best: DoubleArray? = null
        for (s in 0..maxShift) {
            val maxLen = min(n0 - s, nw.size) - tail
            if (maxLen < need) continue
            val xs = ArrayList<Double>(maxLen)
            val ys = ArrayList<Double>(maxLen)
            for (i in 0 until maxLen) {
                val oi = s + i
                if (oi !in old.indices || i !in nw.indices) continue
                if (oldReal != null && (oi >= oldReal.size || !oldReal[oi])) continue
                if (newReal != null && (i >= newReal.size || !newReal[i])) continue
                xs.add(old[oi]); ys.add(nw[i])
            }
            val len = xs.size
            if (len < need) continue
            var mx = 0.0; var my = 0.0
            for (i in 0 until len) { mx += xs[i]; my += ys[i] }
            mx /= len; my /= len
            var vx = 0.0; var cov = 0.0; var vy = 0.0
            for (i in 0 until len) {
                val dx = xs[i] - mx; val dy = ys[i] - my
                vx += dx * dx; cov += dx * dy; vy += dy * dy
            }
            vx /= len; cov /= len; vy /= len
            if (vx < 1e-9) continue
            // Scale is a viewport property, so estimate it from robust central spreads rather
            // than from covariance. OLS attenuates under extraction noise; Deming can swing with
            // changing trace shape. A real affine zoom scales both central spreads equally.
            if (cov <= 1e-12) continue
            val aRaw = robustScale(xs, ys)
            if (!aRaw.isFinite() || aRaw <= 0.0) continue
            val a = aRaw.coerceIn(0.35, 2.8)
            val offsets = DoubleArray(len) { i -> ys[i] - a * xs[i] }
            offsets.sort()
            val b = offsets[len / 2]
            val absErr = DoubleArray(len) { i -> abs(ys[i] - (a * xs[i] + b)) }
            absErr.sort()
            val keep = max(1, (len * 0.85).toInt())
            var sse = 0.0
            for (i in 0 until keep) sse += absErr[i] * absErr[i]
            val res = sqrt(sse / keep) / (sqrt(vy) + 1e-9)
            val sc = res + (if (abs(aRaw - a) > 1e-9) 1.0 else 0.0)
            if (best == null || sc < best[0]) best = doubleArrayOf(sc, s.toDouble(), a, b, aRaw, len.toDouble(), res)
        }
        return best
    }

    /** Ratio of the middle 80% ranges. Exact for an affine zoom and robust to tip/outlier pixels. */
    private fun robustScale(xs: List<Double>, ys: List<Double>): Double {
        if (xs.size < 5 || ys.size != xs.size) return Double.NaN
        val sx = xs.sorted()
        val sy = ys.sorted()
        fun spread(v: List<Double>): Double {
            val lo = ((v.size - 1) * 0.10).toInt()
            val hi = ((v.size - 1) * 0.90).toInt()
            return v[hi] - v[lo]
        }
        val dx = spread(sx)
        val dy = spread(sy)
        return if (dx > 1e-9 && dy > 1e-9) dy / dx else Double.NaN
    }

    private fun scaleIsStable(aFrame: Double, bFrame: Double): Boolean {
        // The retained history is rebased into every accepted frame, so no cumulative affine
        // state is needed for motion. Keep only physically finite per-frame transforms.
        return aFrame.isFinite() && aFrame in 0.35..2.8 && bFrame.isFinite() && abs(bFrame) <= 1.0e5
    }

    private fun tailMedian(p: DoubleArray, real: BooleanArray?): Double {
        val vals = ArrayList<Double>(3)
        if (real != null && real.size == p.size) {
            for (i in p.lastIndex downTo 0) {
                if (real[i]) vals.add(p[i])
                if (vals.size == 3) break
            }
        }
        if (vals.isEmpty()) {
            val from = max(0, p.size - 3)
            for (i in from until p.size) vals.add(p[i])
        }
        if (vals.isEmpty()) return 0.0
        val s = vals.sorted()
        return s[s.size / 2]
    }

    private fun wipeSeries() {
        ts.clear(); vs.clear(); scaleA = 1.0; scaleB = 0.0; regLog.clear()
        provisional = true; lastOkT = -1.0
    }

    private fun clearCandidate() {
        candP = null; candReal = null; candT = -1.0; candA = 1.0; candB = 0.0
        candTs.clear(); candVs.clear(); candProv = true; candMisses = 0
    }

    private fun appendPoint(t: Double, p: DoubleArray, real: BooleanArray?) {
        val med = tailMedian(p, real)
        ts.add(t); vs.add(med)
        while (ts.isNotEmpty() && t - ts[0] > 45.0) { ts.removeAt(0); vs.removeAt(0) }
    }

    /** Existing samples are in the previous frame's coordinates; move all of them together. */
    private fun rebase(values: ArrayList<Double>, a: Double, b: Double) {
        for (i in values.indices) values[i] = a * values[i] + b
    }

    /** Drop points that sit before a hole. The hole is not filled. */
    private fun usableStart(): Int {
        var s = 0
        for (i in 1 until ts.size) if (ts[i] - ts[i - 1] > 3.5) s = i
        return s
    }

    private fun seedTrusted(t: Double, p: DoubleArray, real: BooleanArray?) {
        wipeSeries()
        clearCandidate()
        prevP = p.copyOf(); prevReal = real?.copyOf(); prevT = t
        appendPoint(t, p, real)
        provisional = true
    }

    private fun seedCandidate(t: Double, p: DoubleArray, real: BooleanArray?) {
        candP = p.copyOf(); candReal = real?.copyOf(); candT = t
        candA = 1.0; candB = 0.0; candProv = true
        candTs.clear(); candVs.clear()
        candTs.add(t); candVs.add(tailMedian(p, real)); candMisses = 0
    }

    private fun promoteCandidate() {
        ts.clear(); ts.addAll(candTs)
        vs.clear(); vs.addAll(candVs)
        scaleA = candA; scaleB = candB
        prevP = candP?.copyOf(); prevReal = candReal?.copyOf(); prevT = candT
        provisional = false; lastOkT = candT; failStreak = 0
        regLog.clear()
        // The candidate already earned these accepts. The promoting frame is logged by update().
        for (i in 0 until candTs.size - 1) regLog.add(Pair(candTs[i], "OK"))
        clearCandidate()
    }

    /** Latest frame's in-frame shape. Independent of the registered scale. */
    @Synchronized
    fun localMotion(): TraceGeometry.Motion = TraceGeometry.measure(seenPath, seenReal)

    /** p: path with price-up positive (pixel units). real: true where that column was actually seen. t: monotonic seconds. */
    @Synchronized
    fun update(t: Double, p: DoubleArray, real: BooleanArray? = null): Map<String, Any?> {
        val cleaned = real?.copyOf()
        if (cleaned != null && !TraceGeometry.adoptConnectedTip(p, cleaned)) {
            seenPath = p.copyOf(); seenReal = cleaned
            val vHeld = if (vs.isEmpty()) 0.0 else vs.last()
            if (prevT < 0.0 || t > prevT) {
                lastReg = "FAIL"
                regLog.add(Pair(t, "FAIL"))
                while (regLog.isNotEmpty() && t - regLog[0].first > 45.0) regLog.removeAt(0)
            }
            return mapOf(
                "reg_status" to "FAIL", "reg_shift" to 0.0, "reg_a" to scaleA, "ref_v" to vHeld, "ref_points" to ts.size,
                "reg_reason" to "TIP_DISCONNECTED", "reg_score" to -1.0, "reg_best_shift" to -1.0, "reg_a_raw" to 0.0,
                "reg_a_clamped" to false,
                "reg_b" to scaleB, "reg_overlap_len" to 0, "reg_resid_rel" to -1.0,
                "reg_old_n" to (prevP?.size ?: 0), "reg_new_n" to p.size, "reg_t" to t, "reg_prev_t" to prevT,
                "reg_provisional" to provisional, "reg_fail_streak" to failStreak,
                "reg_candidate_points" to candTs.size,
                "scale_a" to scaleA, "scale_b" to scaleB
            )
        }
        if (cleaned != null) TraceGeometry.stripDetached(cleaned)
        seenPath = p.copyOf(); seenReal = cleaned
        var reg = "NEW"; var logStatus = "NEW"; var shift = 0.0; var aUsed = 1.0
        var reason = "NO_PREVIOUS_FRAME"
        var dScore = -1.0; var dShift = -1.0; var dARaw = 0.0; var dB = 0.0; var dLen = 0; var dRes = -1.0; var dClamped = false
        val pp = prevP
        val prevTBefore = prevT
        val oldN = pp?.size ?: 0
            if (pp == null) {
            seedTrusted(t, p, cleaned)
            logStatus = "NEW"
        } else if (t <= prevT) {
            reg = "GAP"; logStatus = "GAP"; reason = "TIME_NOT_ADVANCING"
        } else {
            val r = register(pp, p, prevReal, cleaned)
            if (r != null) {
                dScore = r[0]; dShift = r[1]; dARaw = r[4]; dB = r[3]; dLen = r[5].toInt(); dRes = r[6]; dClamped = abs(r[4] - r[2]) > 1e-9
            }
            val stable = r != null && r[0] < 0.30 && scaleIsStable(r[2], r[3])
            if (r != null && stable) {
                shift = r[1]; aUsed = r[2]; reg = "OK"; logStatus = "OK"; reason = "ACCEPTED"
                rebase(vs, r[2], r[3])
                scaleA = r[2]
                scaleB = r[3]
                prevP = p.copyOf(); prevReal = cleaned?.copyOf(); prevT = t
                appendPoint(t, p, cleaned)
                failStreak = 0; provisional = false; lastOkT = t
                clearCandidate()
            } else {
                failStreak++
                val why = when {
                    r == null -> "NO_VALID_SHIFT"
                    r[0] < 0.30 -> "SCALE_UNSTABLE"
                    dClamped -> "SCORE_ABOVE_0.30_SCALE_CLAMPED"
                    else -> "SCORE_ABOVE_0.30"
                }
                val unconfirmed = provisional && ts.size < 8
                val lostTooLong = lastOkT >= 0.0 && t - lastOkT > 15.0
                if (unconfirmed || lostTooLong) {
                    // The old geometry is not evidence anymore. This frame starts a new provisional
                    // seed. It is not appended onto the prices it failed to match.
                    seedTrusted(t, p, cleaned)
                    reg = if (lostTooLong) "GAP" else "FAIL"
                    logStatus = reg
                    reason = if (lostTooLong) "INVALIDATED_$why" else "SEED_$why"
                } else {
                    reason = holdOnCandidate(t, p, cleaned, why)
                    // The frame is still a failed accept. Trust ignores it, because trusted history was not changed.
                    if (reason == "PROMOTED_CANDIDATE") {
                        reg = "OK"; logStatus = "OK"
                    } else {
                        reg = "FAIL"; logStatus = "HOLD"
                    }
                }
            }
        }
        if (reason != "TIME_NOT_ADVANCING") {
            lastReg = reg
            regLog.add(Pair(t, logStatus))
            while (regLog.isNotEmpty() && t - regLog[0].first > 45.0) regLog.removeAt(0)
        }
        val v = if (vs.isEmpty()) 0.0 else vs.last()
        return mapOf(
            "reg_status" to reg, "reg_shift" to shift, "reg_a" to aUsed, "ref_v" to v, "ref_points" to ts.size,
            "reg_reason" to reason, "reg_score" to dScore, "reg_best_shift" to dShift, "reg_a_raw" to dARaw,
            "reg_a_clamped" to dClamped,
            "reg_b" to dB, "reg_overlap_len" to dLen, "reg_resid_rel" to dRes,
            "reg_old_n" to oldN, "reg_new_n" to p.size, "reg_t" to t, "reg_prev_t" to prevTBefore,
            "reg_provisional" to provisional, "reg_fail_streak" to failStreak,
            "reg_candidate_points" to candTs.size,
            "scale_a" to scaleA, "scale_b" to scaleB
        )
    }

    /**
     * Trusted polyline and trusted prices stay put. The failed frame can only extend a separate
     * candidate, and only that candidate's own accepts count toward replacing trusted history.
     */
    private fun holdOnCandidate(t: Double, p: DoubleArray, real: BooleanArray?, why: String): String {
        val cp = candP
        if (cp != null && t > candT && t - candT <= 3.5) {
            val cr = register(cp, p, candReal, real)
            if (cr != null && cr[0] < 0.30 && scaleIsStable(cr[2], cr[3])) {
                rebase(candVs, cr[2], cr[3])
                candA = cr[2]
                candB = cr[3]
                candP = p.copyOf(); candReal = real?.copyOf(); candT = t; candProv = false; candMisses = 0
                val med = tailMedian(p, real)
                candTs.add(t); candVs.add(med)
                while (candTs.isNotEmpty() && t - candTs[0] > 45.0) { candTs.removeAt(0); candVs.removeAt(0) }
                val span = candTs.last() - candTs[0]
                if (!candProv && candTs.size >= 8 && span >= 12.0) {
                    promoteCandidate()
                    return "PROMOTED_CANDIDATE"
                }
                return "CANDIDATE_ACCEPTED"
            }
            // One missed frame is temporarily unobservable. It does not append and does not
            // throw away a candidate that was otherwise continuous.
            if (candMisses == 0) {
                candMisses = 1
                return "HELD_$why"
            }
        }
        seedCandidate(t, p, real)
        return "HELD_$why"
    }

    /** Honest series state for logging. Does not itself publish a direction. */
    @Synchronized
    fun seriesStatus(tNow: Double): SeriesStatus {
        if (ts.isEmpty()) return SeriesStatus(0, 0.0, "EMPTY")
        val s0 = usableStart()
        val n = ts.size - s0
        val span = ts.last() - ts[s0]
        val tailAge = tNow - ts.last()
        val reason = when {
            provisional -> "PROVISIONAL"
            tailAge > 2.5 -> "STALE_TAIL"
            n < 8 && span < 12.0 -> "TOO_FEW_AND_TOO_SHORT"
            n < 8 -> "TOO_FEW_POINTS"
            span < 12.0 -> "SPAN_TOO_SHORT"
            else -> "OK"
        }
        return SeriesStatus(n, span, reason)
    }

    /** [ok, fail, gap, hold] in the last windowSec seconds. Hold means the frame was refused and trusted history was kept. */
    @Synchronized
    fun regHistory(tNow: Double, windowSec: Double): IntArray {
        val c = IntArray(4)
        for (e in regLog) if (tNow - e.first <= windowSec) {
            when (e.second) {
                "OK" -> c[0]++
                "FAIL" -> c[1]++
                "GAP" -> c[2]++
                "HOLD" -> c[3]++
            }
        }
        return c
    }

    /** Recent accepted registrations dominate, and the newest acceptance is not stale. */
    @Synchronized
    fun registrationTrusted(tNow: Double): Boolean {
        if (provisional || lastOkT < 0.0 || tNow - lastOkT > 2.5) return false
        val h = regHistory(tNow, 20.0)
        return h[0] >= 8 && h[1] * 4 <= h[0]
    }

    private fun grid(tNow: Double): DoubleArray? {
        if (provisional || ts.size < 8) return null
        val s0 = usableStart()
        if (ts.size - s0 < 8) return null
        val lastT = ts.last()
        if (tNow - lastT > 2.5) return null
        val tRef = min(tNow, lastT)
        if (tRef - ts[s0] < 12.0) return null
        val w = min(30.0, tRef - ts[s0]).toInt()
        val g = DoubleArray(w + 1)
        var seg = s0
        for (i in 0..w) {
            val tg = tRef - (w - i).toDouble()
            while (seg < ts.size - 2 && ts[seg + 1] < tg) seg++
            if (seg < s0) seg = s0
            val t0 = ts[seg]; val t1 = ts[min(seg + 1, ts.size - 1)]
            val f = if (t1 > t0) ((tg - t0) / (t1 - t0)).coerceIn(0.0, 1.0) else 0.0
            val v1 = vs[min(seg + 1, vs.size - 1)]
            g[i] = vs[seg] + (v1 - vs[seg]) * f
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
        return Pair(v3, (v3 - v3p) / 3.0)
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
            against, impz, er, state, u, ts.size - usableStart(), ts.last() - ts[usableStart()]
        )
    }

    /**
     * Established local wave from the registered path.
     * Slopes are price-up. The correction envelope is half the prior swing, the same
     * retrace bar entry safety already uses. A move that lives mostly inside the last
     * three seconds has not become that swing.
     */
    @Synchronized
    fun wave(tNow: Double): WaveRead {
        val g = grid(tNow) ?: return WaveRead(false)
        val w = g.size - 1
        if (w < 12) return WaveRead(false)
        val fe = features(tNow, 1)
        if (!fe.valid) return WaveRead(false)
        val z3 = fe.z3
        val z6 = fe.z6
        val z10 = fe.z10
        val z20 = fe.z20
        fun sgn(z: Double, bar: Double): Int = when {
            z >= bar -> 1
            z <= -bar -> -1
            else -> 0
        }
        val d3 = abs(g[w] - g[w - min(3, w)])
        val d10 = abs(g[w] - g[w - min(10, w)])
        val d20 = abs(g[w] - g[w - min(20, w)])
        // Half of the 10-second displacement sitting inside 3 seconds means the move
        // has not spread through the medium window. 1.5 is the existing impulse bar.
        val concentrated = abs(z3) >= 1.5 && d10 > fe.stepScale && d3 > 0.5 * d10
        val sameWayEstablished = abs(z20) >= 0.5 && sgn(z20, 0.5) == sgn(z3, 0.0) && d20 > 0.0 && d3 <= 0.5 * d20
        val shock = concentrated && !sameWayEstablished
        val impulseSign = sgn(z3, 0.0)
        val span = min(20, w)
        val from = w - span
        var hi = from
        var lo = from
        for (i in from..w) {
            if (g[i] >= g[hi]) hi = i
            if (g[i] <= g[lo]) lo = i
        }
        val range = g[hi] - g[lo]
        val s20 = sgn(z20, 0.5)
        val s10 = sgn(z10, 0.5)
        val s6 = sgn(z6, 0.5)
        val prior = when {
            s20 != 0 -> s20
            s10 != 0 && s6 == s10 && !shock -> s10
            else -> 0
        }
        val retrace = if (prior == 0 || range <= fe.stepScale) 0.0 else if (prior > 0) {
            ((g[hi] - g[w]) / range).coerceIn(0.0, 1.0)
        } else {
            ((g[w] - g[lo]) / range).coerceIn(0.0, 1.0)
        }
        // The new leg has to show up in both the 6s and 10s windows, and it has to
        // have given back at least half the prior swing. A 3-second poke does neither.
        val counterConfirmed = prior != 0 && s10 == -prior && s6 == s10 && abs(z10) >= 0.5
        val broken = counterConfirmed && retrace >= 0.5 && !shock
        val dominant = if (broken) -prior else prior
        val phase = when {
            shock -> "SHOCK"
            broken -> "REVERSAL"
            prior != 0 && impulseSign == -prior && abs(z3) >= 0.5 && retrace < 0.5 -> "PULLBACK"
            prior != 0 && s10 == -prior && retrace < 0.5 -> "PULLBACK"
            prior == 0 && s20 != 0 && s10 != 0 && s10 != s20 -> "UNSTABLE"
            dominant != 0 -> "CONTINUING"
            else -> "NONE"
        }
        return WaveRead(true, dominant, phase, retrace, broken, shock, impulseSign, z3, z6, z10, z20)
    }
}

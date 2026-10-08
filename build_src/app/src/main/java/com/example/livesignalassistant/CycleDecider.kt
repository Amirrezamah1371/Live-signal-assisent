package com.example.livesignalassistant

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class Obs(val tMs: Long, val r: SignalResult)

/**
 * 90-second cycle decision (72.0).
 *  - uses ALL side-bearing observations (WAIT observations keep their proposed side at low weight),
 *  - recency is measured in SECONDS (half-life 25 s) and the 80-90 s window gets x1.6 weight,
 *  - ChangeEngine supplies real recent-change features; a REGIME_CHANGE needs multi-window agreement,
 *    persistence, a deep retrace and time since the extreme (an isolated spike cannot flip the side),
 *  - entry quality and decision strength are separate; strength is NOT a probability,
 *  - Experience may only lower/raise strength or turn the signal into WAIT.
 */
object CycleDecider {
    private val weakReasons = setOf("FILTER", "SAME_SETUP", "ANTI_FLIP", "CONFIRM", "DIRECTION_UNSTABLE", "LATE_ENTRY_RISK", "SHOCK_UNRESOLVED")
    private val lateReasons = setOf("LATE_ENTRY_RISK", "SHOCK_UNRESOLVED")

    private fun num(r: SignalResult, k: String): Double? = (r.diagnostics[k] as? Number)?.toDouble()
    private fun clip01(v: Double) = v.coerceIn(0.0, 1.0)

    fun decide(
        list: List<Obs>, endMs: Long, ce: ChangeEngine, tNowSec: Double,
        experience: ExperienceStore, staleMs: Long
    ): SignalResult {
        val n = list.size
        fun tw(o: Obs): Double {
            val age = (endMs - o.tMs).coerceAtLeast(0L) / 1000.0
            return 0.5.pow(age / 25.0) * (if (o.tMs >= 80000L) 1.6 else 1.0)
        }
        fun ef(r: SignalResult) = 0.55 + r.entryQuality.coerceIn(0, 100) / 220.0

        var upW = 0.0; var dnW = 0.0; var dirN = 0; var weakN = 0; var lowVis = 0
        var lateW = 0.0; var sideW30 = 0.0
        for (o in list) {
            val r = o.r
            if (r.reason == "LOW_VISIBILITY") { lowVis++; continue }
            val dirObs = r.direction == "UP" || r.direction == "DOWN"
            val weak = !dirObs && r.side != 0 && r.reason in weakReasons
            if (!dirObs && !weak) continue
            val w = tw(o) * ef(r) * (if (dirObs) 1.0 else 0.30)
            val sgn = if (dirObs) (if (r.direction == "UP") 1 else -1) else r.side
            if (sgn > 0) upW += w else dnW += w
            if (dirObs) dirN++ else weakN++
            if (endMs - o.tMs <= 30000L) {
                sideW30 += w
                if (weak && r.reason in lateReasons) lateW += w
            }
        }
        val total = upW + dnW
        val cons = if (total <= 0.0) 0.0 else max(upW, dnW) / total
        val baseSide = when {
            upW >= dnW * 1.15 && upW > 0.0 -> "UP"
            dnW >= upW * 1.15 && dnW > 0.0 -> "DOWN"
            else -> "WAIT"
        }
        val lateShare = if (sideW30 > 0.0) lateW / sideW30 else 0.0
        val last20 = list.filter { endMs - it.tMs <= 20000L }
        val extQ = if (last20.isEmpty()) 0.0 else last20.map { it.r.traceQuality }.average()

        val common = HashMap<String, Any?>()
        common["cycle_mode"] = "90S_80_PLUS_10"
        common["deep_observation_ms"] = 80000; common["final_window_ms"] = 10000
        common["fixed_horizon_ms"] = 60000; common["timeframe"] = "1m"
        common["path_usable"] = dirN; common["path_weak_side"] = weakN; common["low_visibility_obs"] = lowVis
        common["up_weight"] = upW; common["down_weight"] = dnW; common["path_consistency"] = cons
        common["base_side"] = baseSide; common["late_share"] = lateShare; common["ext_quality"] = extQ
        common["reg_status"] = ce.lastReg
        val ss = ce.seriesStatus(tNowSec)
        common["series_points"] = ss.points; common["series_span_s"] = ss.spanSec
        common["recent_invalid_reason"] = ss.reason; common["recent_valid"] = ss.reason == "OK"
        val rh = ce.regHistory(tNowSec, 20.0)
        common["reg_ok_20s"] = rh[0]; common["reg_fail_20s"] = rh[1]; common["reg_gap_20s"] = rh[2]; common["reg_new_20s"] = rh[3]

        fun wait(reason: String, extra: Map<String, Any?> = emptyMap()) = SignalResult(
            "WAIT", 60, -1, reason = reason, diagnostics = common + extra
        )

        if (staleMs > 4000L) return wait("STALE_OBSERVATIONS", mapOf("stale_ms" to staleMs))
        if (n < 45) return wait("INSUFFICIENT_OBSERVATIONS")
        if (dirN < 4) return wait("FEW_DIRECTIONAL")

        // ---------- recent-change arbitration ----------
        val baseSign = if (baseSide == "UP") 1 else if (baseSide == "DOWN") -1 else 0
        val feBase = ce.features(tNowSec, baseSign)
        var side = baseSign
        var corrected = false
        var recentLed = false
        val zc = 0.2 * feBase.z3 + 0.3 * feBase.z6 + 0.3 * feBase.z10 + 0.2 * feBase.z20
        val recentSide = if (!feBase.valid) "WAIT" else if (zc > 0.15) "UP" else if (zc < -0.15) "DOWN" else "WAIT"
        if (baseSign == 0) {
            if (feBase.valid && abs(feBase.agreement) >= 0.999 && abs(feBase.z10) >= 1.0 && abs(feBase.z6) >= 0.8) {
                val cand = if (zc > 0) 1 else -1
                val f2 = ce.features(tNowSec, cand)
                if (f2.valid && f2.state == "CONTINUING") { side = cand; recentLed = true }
            }
            if (side == 0) return wait("CYCLE_INCOHERENT", mapOf("final_recent_side" to recentSide))
        } else if (feBase.valid && feBase.state == "REGIME_CHANGE") {
            side = -baseSign; corrected = true
        } else if (cons < 0.60) {
            return wait("CYCLE_INCOHERENT", mapOf("final_recent_side" to recentSide))
        }
        val sideStr = if (side > 0) "UP" else "DOWN"
        val fe = ce.features(tNowSec, side)
        val state = if (corrected) "REGIME_CHANGE" else if (fe.valid) fe.state else "NO_RECENT"

        // ---------- aggregates on the chosen side (last 20 s) ----------
        var sw = 0.0; var eSum = 0.0; var cSum = 0.0; var xSum = 0.0; var rSum = 0.0; var rW = 0.0
        var topReason = "TREND"; var topW = 0.0
        for (o in last20) {
            val r = o.r
            if (r.reason == "LOW_VISIBILITY") continue
            val dirObs = r.direction == "UP" || r.direction == "DOWN"
            val s = if (dirObs) (if (r.direction == "UP") 1 else -1) else r.side
            val t = tw(o)
            num(r, "trend_regime")?.let { rSum += it * t; rW += t }
            if (s != side) continue
            val w = t * (if (dirObs) 1.0 else 0.30)
            sw += w; eSum += r.entryQuality * w; cSum += r.conflict * w
            xSum += (num(r, "exhaustion") ?: 0.0) * w
            if (dirObs && w > topW) { topW = w; topReason = r.reason }
        }
        val entryBase = if (sw > 0.0) eSum / sw else 50.0
        val conflict = if (sw > 0.0) cSum / sw else 50.0
        val exhaustion = if (sw > 0.0) xSum / sw else 0.0
        val regime = ExperienceStore.regimeBand(if (rW > 0.0) rSum / rW else 0.5)

        // ---------- entry quality (separate from direction) ----------
        var pen = 0.0
        val pens = ArrayList<String>()
        if (state == "EXHAUSTION") { pen += 22.0; pens.add("EXHAUSTION") }
        if (fe.valid && side * 1.0 * fe.z3 * 1.0 <= -0.7 && !corrected) { pen += 18.0; pens.add("INTO_COUNTER_MOVE") }
        if (state == "PULLBACK") { pen += 8.0; pens.add("PULLBACK") }
        if (state == "NOISE") { pen += 15.0; pens.add("NOISE") }
        pen += 20.0 * (1.0 - extQ)
        pen += 0.15 * conflict
        pen += 15.0 * lateShare
        val entryQ = (entryBase - pen).coerceIn(0.0, 100.0)

        // ---------- decision strength (NOT a probability) ----------
        val sideW = if (side > 0) upW else dnW
        val otherW = if (side > 0) dnW else upW
        val margin = if (total > 0.0) (sideW - otherW) / total else 0.0
        val agree = if (fe.valid) fe.agreement else 0.0
        val cVote = if (corrected) clip01(agree * 0.5 + 0.5) else clip01((margin - 0.1) / 0.7)
        val cCons = if (corrected) 0.5 else clip01((cons - 0.5) / 0.5)
        val cRecent = if (fe.valid) (agree + 1.0) / 2.0 else 0.5
        val e = 0.26 * cVote + 0.18 * cCons + 0.22 * cRecent + 0.18 * (entryQ / 100.0) + 0.10 * extQ +
            0.06 * clip01(dirN / 20.0) - 0.20 * clip01(conflict / 100.0) - 0.15 * clip01(exhaustion) -
            (if (corrected) 0.10 else 0.0) - (if (fe.valid) 0.0 else 0.06)
        val strength0 = 100.0 / (1.0 + exp(-7.0 * (e - 0.42)))

        // ---------- Experience (reaches the decision here) ----------
        val band = ExperienceStore.entryBand(entryQ)
        val adv = experience.advise(sideStr, state, regime, band)
        val strength = (strength0 + (if (adv.action == "AVOID") 0.0 else adv.strengthDelta)).coerceIn(0.0, 100.0)

        val out = HashMap<String, Any?>(common)
        out["final_recent_side"] = recentSide
        out["final_corrected"] = corrected; out["final_change_confirmed"] = corrected
        out["direction_change_reason"] = if (corrected) "REGIME_CHANGE_MULTI_WINDOW" else if (recentLed) "RECENT_LED" else "NONE"
        out["recent_valid"] = fe.valid; out["final_state"] = state
        out["slope_3s"] = fe.z3; out["slope_6s"] = fe.z6; out["slope_10s"] = fe.z10; out["slope_20s"] = fe.z20
        out["trajectory_accel_now"] = fe.acceleration; out["trajectory_jerk"] = fe.jerk
        out["counter_magnitude"] = fe.counter; out["persistence_decay"] = fe.persistenceDecay
        out["recent_direction_flips"] = fe.flips; out["velocity_ratio"] = fe.velocityRatio
        out["impulse_z"] = fe.impulseZ; out["efficiency_ratio"] = fe.efficiency
        out["since_peak_s"] = fe.sincePeakSec; out["against_steps_8"] = fe.against8
        out["step_scale"] = fe.stepScale
        out["window_agreement"] = agree
        out["entry_base"] = entryBase; out["entry_penalties"] = pens.joinToString(",")
        out["entry_quality"] = entryQ; out["conflict_value"] = conflict; out["exhaustion"] = exhaustion
        out["strength_raw"] = strength0; out["strength_evidence"] = e
        out["vote_margin"] = margin
        out["ctx_state"] = state; out["ctx_regime"] = regime; out["ctx_band"] = band
        out["experience_applied"] = adv.action != "NONE"; out["experience_action"] = adv.action
        out["experience_delta"] = if (adv.action == "AVOID") 0.0 else adv.strengthDelta
        out["experience_samples"] = adv.nEff; out["experience_posterior"] = adv.posterior
        out["experience_lb80"] = adv.lb80; out["experience_ub80"] = adv.ub80
        out["experience_level"] = adv.level; out["experience_key"] = adv.key
        out["experience_note"] = adv.note; out["experience_alt_posterior"] = adv.altPosterior
        out["experience_veto"] = adv.action == "AVOID"

        val reasonWait = when {
            adv.action == "AVOID" -> "EXPERIENCE_AVOID"
            entryQ < 32.0 -> "POOR_ENTRY"
            strength < 22.0 -> "WEAK_EVIDENCE"
            else -> ""
        }
        if (reasonWait.isNotEmpty())
            return SignalResult("WAIT", 60, -1, entryQ.toInt(), conflict.toInt(), reasonWait, diagnostics = out)
        val reason = if (corrected) "REGIME_CHANGE" else if (recentLed) "RECENT_LED" else topReason
        return SignalResult(
            sideStr, 60, -1, entryQ.toInt(), conflict.toInt(), reason,
            signalQuality = max(1, strength.toInt()), diagnostics = out
        )
    }
}

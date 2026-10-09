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
 *  - Experience may only lower strength or turn the signal into WAIT. It does not raise strength.
 *  - A direction is published only when the recent cross-frame series is valid and registration is trusted.
 */
object CycleDecider {
    private val weakReasons = setOf("FILTER", "SAME_SETUP", "ANTI_FLIP", "CONFIRM", "DIRECTION_UNSTABLE", "LATE_ENTRY_RISK", "SHOCK_UNRESOLVED")
    private val lateReasons = setOf("LATE_ENTRY_RISK", "SHOCK_UNRESOLVED")

    private fun num(r: SignalResult, k: String): Double? = (r.diagnostics[k] as? Number)?.toDouble()
    private fun clip01(v: Double) = v.coerceIn(0.0, 1.0)

    fun decide(
        list: List<Obs>, endMs: Long, ce: ChangeEngine, tNowSec: Double,
        experience: ExperienceSource, staleMs: Long
    ): SignalResult {
        val n = list.size
        fun tw(o: Obs): Double {
            val age = (endMs - o.tMs).coerceAtLeast(0L) / 1000.0
            return 0.5.pow(age / 25.0) * (if (o.tMs >= 80000L) 1.6 else 1.0)
        }
        fun ef(r: SignalResult) = 0.55 + r.entryQuality.coerceIn(0, 100) / 220.0

        var upW = 0.0; var dnW = 0.0; var dirN = 0; var weakN = 0; var lowVis = 0
        var lateW = 0.0; var sideW30 = 0.0
        var refuseW = 0.0; var directW = 0.0
        for (o in list) {
            val r = o.r
            if (r.reason == "LOW_VISIBILITY" || r.reason == "NO_CURRENT_TIP") { lowVis++; continue }
            val dirObs = r.direction == "UP" || r.direction == "DOWN"
            val weak = !dirObs && r.side != 0 && r.reason in weakReasons
            if (!dirObs && !weak) continue
            val w = tw(o) * ef(r) * (if (dirObs) 1.0 else 0.30)
            // Late-entry and unresolved-shock observations are refusals. They must not vote for a direction.
            // Their full weight is kept so a few structural votes cannot outvote an explicit "do not enter".
            if (weak && r.reason in lateReasons) {
                val refusal = tw(o) * ef(r)
                refuseW += refusal
                if (endMs - o.tMs <= 30000L) lateW += w
                continue
            }
            val sgn = if (dirObs) (if (r.direction == "UP") 1 else -1) else r.side
            if (sgn > 0) upW += w else dnW += w
            if (dirObs) {
                dirN++; directW += tw(o) * ef(r)
            } else weakN++
            if (endMs - o.tMs <= 30000L) sideW30 += w
        }
        val total = upW + dnW
        val cons = if (total <= 0.0) 0.0 else max(upW, dnW) / total
        val baseSide = when {
            upW >= dnW * 1.15 && upW > 0.0 -> "UP"
            dnW >= upW * 1.15 && dnW > 0.0 -> "DOWN"
            else -> "WAIT"
        }
        val lateShare = if (sideW30 + lateW > 0.0) lateW / (sideW30 + lateW) else 0.0
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
        common["reg_ok_20s"] = rh[0]; common["reg_fail_20s"] = rh[1]; common["reg_gap_20s"] = rh[2]; common["reg_hold_20s"] = rh[3]

        fun slopeDiag(fe: ChangeFeatures, recent: String) = mapOf(
            "final_recent_side" to recent,
            "final_state" to fe.state,
            "features_valid" to fe.valid,
            "slope_3s" to fe.z3, "slope_6s" to fe.z6, "slope_10s" to fe.z10, "slope_20s" to fe.z20,
            "window_agreement" to fe.agreement
        )
        fun wait(reason: String, extra: Map<String, Any?> = emptyMap()) = SignalResult(
            "WAIT", 60, -1, reason = reason, diagnostics = common + extra
        )

        if (staleMs > 4000L) return wait("STALE_OBSERVATIONS", mapOf("stale_ms" to staleMs))
        if (n < 45) return wait("INSUFFICIENT_OBSERVATIONS")

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
                val cand = if (zc > 0.15) 1 else if (zc < -0.15) -1 else 0
                if (cand != 0) {
                    val f2 = ce.features(tNowSec, cand)
                    if (f2.valid && f2.state == "CONTINUING") { side = cand; recentLed = true }
                }
            }
            if (side == 0) return wait("CYCLE_INCOHERENT", slopeDiag(feBase, recentSide))
        } else if (feBase.valid && feBase.state == "REGIME_CHANGE") {
            side = -baseSign; corrected = true
        } else if (cons < 0.60) {
            return wait("CYCLE_INCOHERENT", slopeDiag(feBase, recentSide))
        }
        val sideStr = if (side > 0) "UP" else "DOWN"
        val fe = ce.features(tNowSec, side)
        val state = if (corrected) "REGIME_CHANGE" else if (fe.valid) fe.state else "NO_RECENT"
        // Cropping the trace at the last real column makes most analyzer frames FILTER, so dirN
        // often stays under 4 even when the registered path is a clean continuation. The waiver
        // is the existing recent-led test, not a new cutoff: full-window agreement, a material
        // 6s and 10s slope, and CONTINUING. Vision, entry safety, and NO_RECENT still apply.
        val pathConfirmed = fe.valid && abs(fe.agreement) >= 0.999 && abs(fe.z10) >= 1.0 &&
            abs(fe.z6) >= 0.8 && state == "CONTINUING"
        if (dirN < 4 && !pathConfirmed) {
            return wait("FEW_DIRECTIONAL", mapOf(
                "final_recent_side" to recentSide,
                "final_state" to state,
                "features_valid" to fe.valid,
                "slope_3s" to fe.z3, "slope_6s" to fe.z6, "slope_10s" to fe.z10, "slope_20s" to fe.z20,
                "window_agreement" to fe.agreement,
                "path_confirmed" to false
            ))
        }

        // ---------- aggregates on the chosen side (last 20 s) ----------
        var sw = 0.0; var eSum = 0.0; var cSum = 0.0; var xSum = 0.0; var rSum = 0.0; var rW = 0.0
        var topReason = "PATH"; var topW = 0.0
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
        // Missing recent evidence is absence, not a neutral 0.5 agreement.
        val cRecent = if (fe.valid) (agree + 1.0) / 2.0 else 0.0
        val e = 0.26 * cVote + 0.18 * cCons + 0.22 * cRecent + 0.18 * (entryQ / 100.0) + 0.10 * extQ +
            0.06 * clip01(dirN / 20.0) - 0.20 * clip01(conflict / 100.0) - 0.15 * clip01(exhaustion) -
            (if (corrected) 0.10 else 0.0) - (if (fe.valid) 0.0 else 0.06)
        val strength0 = 100.0 / (1.0 + exp(-7.0 * (e - 0.42)))

        // ---------- Experience (reaches the decision here) ----------
        val band = ExperienceStore.entryBand(entryQ)
        val adv = experience.advise(sideStr, state, regime, band)
        val strength = (strength0 + (if (adv.action == "AVOID") 0.0 else adv.strengthDelta)).coerceIn(0.0, 100.0)
        val regTrusted = ce.registrationTrusted(tNowSec)
        // Maturity uses the exhaustion of the side being entered. Refusal frames stay in refuseWeight.
        val local = ce.localMotion()
        val disagree = fe.valid && TraceGeometry.channelsDisagree(fe.z3, local)
        val legBar = max(4.0 * local.step, 4.0)
        // Same material-leg bar the channel check uses. A counter-tick under it is not a refusal.
        val materialBar = max(4.0 * local.step, 0.15 * local.swing)
        val retraceAgainst = if (!local.usable) 0.0 else if (side > 0) local.retraceHigh else local.retraceLow
        val legAgainst = local.usable && if (side > 0) local.leg < -legBar else local.leg > legBar
        val materialLegAgainst = local.usable && abs(local.leg) > materialBar &&
            ((side > 0 && local.leg < 0.0) || (side < 0 && local.leg > 0.0))
        var finSup = 0.0
        var finOpp = 0.0
        for (o in list) {
            if (o.tMs < 80000L) continue
            val r = o.r
            if (r.direction != "UP" && r.direction != "DOWN") continue
            val w = tw(o) * ef(r)
            val sgn = if (r.direction == "UP") 1 else -1
            if (sgn == side) finSup += w else finOpp += w
        }
        val recentOpposes = (recentSide == "UP" && sideStr == "DOWN") || (recentSide == "DOWN" && sideStr == "UP")
        val inFrameOpposes = local.usable && abs(local.net) > legBar &&
            ((side > 0 && local.net < 0.0) || (side < 0 && local.net > 0.0))
        val votesOppose = finOpp > 0.0 && finOpp >= finSup * 1.15
        // A confirmed regime flip already followed the recent path. A continuation that the
        // recent path and either the frame or the final window reject is a stale base side.
        val staleDirection = fe.valid && !corrected && recentOpposes && (inFrameOpposes || votesOppose)
        val safety = if (fe.valid) EntrySafety.block(
            fe.agreement, state, exhaustion, fe.impulseZ, fe.velocityRatio,
            fe.sincePeakSec, fe.counter, refuseW, directW,
            side * fe.z3, side * fe.z6, side * fe.z10,
            retraceAgainst, legAgainst, materialLegAgainst
        ) else ""

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
        out["reg_trusted"] = regTrusted
        out["entry_safety"] = safety
        out["refuse_weight"] = refuseW
        out["direct_weight"] = directW
        out["safety_exhaustion"] = exhaustion
        out["strength_is_probability"] = false
        out["vision_reliability"] = when {
            disagree || !local.usable -> 0.0
            fe.valid -> 1.0
            else -> 0.0
        }
        out["direction_confidence"] = strength0
        out["channel_disagree"] = disagree
        out["local_net"] = local.net
        out["local_retrace_against"] = retraceAgainst
        out["local_leg"] = local.leg
        out["stale_direction"] = staleDirection
        out["scale_a"] = ce.debugState()["scale_a"]
        out["path_confirmed"] = pathConfirmed

        val reasonWait = DecisionGate.block(
            fe.valid, regTrusted, state, lateShare, entryQ, strength, adv.action, safety,
            visionReliable = fe.valid && local.usable && !disagree,
            staleDirection = staleDirection
        )
        if (reasonWait.isNotEmpty())
            return SignalResult("WAIT", 60, -1, entryQ.toInt(), conflict.toInt(), reasonWait, diagnostics = out)
        val reason = if (corrected) "REGIME_CHANGE" else if (recentLed) "RECENT_LED" else topReason
        return SignalResult(
            sideStr, 60, -1, entryQ.toInt(), conflict.toInt(), reason,
            signalQuality = max(1, strength.toInt()), diagnostics = out
        )
    }
}

/** Publish gate. Empty string means the directional result may be shown. */
object DecisionGate {
    fun block(
        recentValid: Boolean,
        regTrusted: Boolean,
        state: String,
        lateShare: Double,
        entryQ: Double,
        strength: Double,
        experienceAction: String,
        safety: String = "",
        visionReliable: Boolean = true,
        staleDirection: Boolean = false
    ): String = when {
        !recentValid -> "NO_RECENT_EVIDENCE"
        !visionReliable -> "VISION_UNRELIABLE"
        !regTrusted -> "REGISTRATION_UNSTABLE"
        state == "EXHAUSTION" -> "EXHAUSTION"
        state == "NOISE" -> "NOISE"
        safety.isNotEmpty() -> safety
        staleDirection -> "STALE_DIRECTION"
        lateShare >= 0.5 -> "LATE_WINDOW"
        experienceAction == "AVOID" -> "EXPERIENCE_AVOID"
        entryQ < 32.0 -> "POOR_ENTRY"
        strength < 22.0 -> "WEAK_EVIDENCE"
        else -> ""
    }
}

/**
 * Stage B. Direction may already be known. This asks whether now is a safe 1-minute entry.
 * Structural labels do not reach this object, so they cannot waive it.
 * Empty string means the entry is allowed to continue to the remaining gates.
 */
object EntrySafety {
    fun block(
        agreement: Double,
        state: String,
        exhaustion: Double,
        impulseZ: Double,
        velocityRatio: Double,
        sincePeakSec: Int,
        counter: Double,
        refuseWeight: Double,
        directWeight: Double,
        signedZ3: Double = Double.NaN,
        signedZ6: Double = Double.NaN,
        signedZ10: Double = Double.NaN,
        retraceAgainst: Double = 0.0,
        legAgainst: Boolean = false,
        materialLegAgainst: Boolean = false
    ): String {
        // 0.50 is the existing pullback bar (side * slope), not a fitted trade threshold.
        // Two of the three recent windows must clear it before the side is called opposed.
        val slopesKnown = !signedZ3.isNaN() && !signedZ6.isNaN() && !signedZ10.isNaN()
        if (slopesKnown) {
            val opposed = listOf(signedZ3, signedZ6, signedZ10).count { it <= -0.50 }
            if (opposed >= 2) return "AGAINST_RECENT"
        } else if (agreement < 0.0) {
            return "AGAINST_RECENT"
        }
        // The 6s window still opposes. That is not evidence the pullback has resumed.
        if (state == "PULLBACK") return "PULLBACK_UNRESOLVED"
        // The raw tip leg is an independent channel. It can oppose the side when the registered
        // 3-second slope is too weak to count as AGAINST_RECENT and the swing has not retraced halfway.
        if (materialLegAgainst) return "RAW_LEG_OPPOSES"
        // Half of the visible swing given back, and the latest leg already going the other way.
        // Sitting on the extreme itself has retrace 0 and is not this case.
        if (retraceAgainst >= 0.5 && legAgainst) return "FAILED_EXTREME"
        val atExtreme = sincePeakSec <= 3 && counter < 0.5
        // 0.62 is the analyzer late-entry bar. 1.5 is the existing impulse bar.
        // Velocity under 0.80 means the current 3s speed is no longer at the peak of this window.
        if (atExtreme && exhaustion >= 0.62 && impulseZ >= 1.5 && velocityRatio < 0.80) return "MATURE_IMPULSE"
        // Explicit do-not-enter mass, not a minimum vote count. A quiet cycle has refuseWeight 0.
        // Refusals must be more than twice the directional weight before they veto. A near-tie is not
        // "most of the evidence says do not enter".
        if (refuseWeight > directWeight * 2.0 && refuseWeight > 0.0) return "ENTRY_REFUSED"
        return ""
    }
}

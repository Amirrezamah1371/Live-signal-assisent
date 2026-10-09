package com.example.livesignalassistant

import kotlin.math.abs
import kotlin.math.max

/**
 * Intra-cycle opportunity. The 90-second mark is the latest the observation window may run,
 * not the moment a decision is allowed. This does not call [CycleDecider].
 *
 * A qualified frame can publish as soon as the fresh registration and the current frame agree.
 * One setup is published once. The trade's own expiry clock is not stored here, so resetting
 * the observation window cannot move it.
 */
object OpportunityDecider {
    fun evaluate(
        frame: SignalResult,
        ce: ChangeEngine,
        tNowSec: Double,
        experience: ExperienceSource
    ): SignalResult {
        val measured = (frame.diagnostics["measured_direction"] as? String)
            ?: if (frame.direction == "UP" || frame.direction == "DOWN") frame.direction else "WAIT"
        val entryRefused = frame.reason == "LATE_ENTRY_RISK" || frame.reason == "SHOCK_UNRESOLVED" ||
            frame.diagnostics["entry_permission"] == "REFUSED"
        fun hold(reason: String): SignalResult {
            val permission = if (entryRefused || reason == "LATE_ENTRY_RISK" || reason == "SHOCK_UNRESOLVED") "REFUSED" else "CLOSED"
            return SignalResult(
                "WAIT", 60, frame.confidence, frame.entryQuality, frame.conflict, reason,
                diagnostics = frame.diagnostics + mapOf(
                    "measured_direction" to measured,
                    "entry_permission" to permission,
                    "opportunity" to "FRESH"
                ),
                side = if (measured == "DOWN") -1 else if (measured == "UP") 1 else frame.side,
                traceQuality = frame.traceQuality
            )
        }
        if (!ce.registrationTrusted(tNowSec)) return hold("REGISTRATION_UNSTABLE")
        if (ce.seriesStatus(tNowSec).reason != "OK") return hold("NO_RECENT_EVIDENCE")
        if (measured != "UP" && measured != "DOWN") {
            return hold(if (frame.reason.isEmpty()) "NO_DIRECTION" else frame.reason)
        }
        val sign = if (measured == "UP") 1 else -1
        val fe = ce.features(tNowSec, sign)
        if (!fe.valid) return hold("NO_RECENT_EVIDENCE")
        val local = ce.localMotion()
        if (!local.usable || TraceGeometry.channelsDisagree(fe.z3, local)) return hold("VISION_UNRELIABLE")
        if (entryRefused) return hold(frame.reason)
        // The short registered move and the current frame have to name the same side.
        if (sign * fe.z3 <= 0.0 || sign * fe.z6 <= 0.0) return hold("CURRENT_DIRECTION_UNSUPPORTED")
        if (fe.state == "EXHAUSTION") return hold("EXHAUSTION")
        if (fe.state == "NOISE") return hold("NOISE")
        // A frame the analyzer did not itself qualify is not promoted here.
        if (frame.direction != measured) return hold(if (frame.reason.isEmpty()) "FILTER" else frame.reason)
        if (frame.confidence < 64 || frame.entryQuality < 47 || frame.conflict > 57) return hold("WEAK_EVIDENCE")
        val legBar = max(4.0 * local.step, 4.0)
        val materialBar = max(4.0 * local.step, 0.15 * local.swing)
        val retraceAgainst = if (sign > 0) local.retraceHigh else local.retraceLow
        val legAgainst = if (sign > 0) local.leg < -legBar else local.leg > legBar
        val materialLegAgainst = abs(local.leg) > materialBar &&
            ((sign > 0 && local.leg < 0.0) || (sign < 0 && local.leg > 0.0))
        val exhaustion = (frame.diagnostics["exhaustion"] as? Double) ?: 0.0
        val safety = EntrySafety.block(
            fe.agreement, fe.state, exhaustion, fe.impulseZ, fe.velocityRatio,
            fe.sincePeakSec, fe.counter, 0.0, 1.0,
            sign * fe.z3, sign * fe.z6, sign * fe.z10,
            retraceAgainst, legAgainst, materialLegAgainst
        )
        if (safety.isNotEmpty()) return hold(safety)
        val trend = (frame.diagnostics["trend_regime"] as? Double) ?: 0.5
        val regime = ExperienceStore.regimeBand(trend)
        val band = ExperienceStore.entryBand(frame.entryQuality.toDouble())
        val adv = experience.advise(measured, fe.state, regime, band)
        if (adv.action == "AVOID") return hold("EXPERIENCE_AVOID")
        val key = "$measured|${frame.reason}|${frame.entryQuality / 8}"
        return SignalResult(
            measured, 60, frame.confidence, frame.entryQuality, frame.conflict, frame.reason,
            signalQuality = max(1, frame.confidence),
            diagnostics = frame.diagnostics + mapOf(
                "measured_direction" to measured,
                "entry_permission" to "OPEN",
                "opportunity" to "FRESH",
                "setup_key" to key,
                "ctx_state" to fe.state,
                "ctx_regime" to regime,
                "ctx_band" to band,
                "reg_trusted" to true,
                "reg_status" to ce.lastReg
            ),
            side = sign,
            traceQuality = frame.traceQuality
        )
    }
}

/**
 * Observation window. Publishing and the user's trade timer are separate.
 * [onUserLocked] starts a new 90-second maximum window and does not receive the trade clock.
 */
class OpportunityCycle {
    var startMs: Long = 0L
        private set
    var resetAtMs: Long = 90_000L
        private set
    var bubbleOpen: Boolean = false
        private set
    var publishedKey: String = ""
        private set
    var publishedThisWindow: Boolean = false
        private set

    fun start(nowMs: Long) {
        startMs = nowMs
        resetAtMs = nowMs + 90_000L
        bubbleOpen = false
        publishedKey = ""
        publishedThisWindow = false
    }

    /** Null when this frame is not a new qualified opportunity. */
    fun consider(
        frame: SignalResult,
        ce: ChangeEngine,
        tNowSec: Double,
        experience: ExperienceSource
    ): SignalResult? {
        if (bubbleOpen) return null
        val opp = OpportunityDecider.evaluate(frame, ce, tNowSec, experience)
        if (opp.direction != "UP" && opp.direction != "DOWN") return null
        val key = opp.diagnostics["setup_key"] as? String ?: "${opp.direction}|${opp.reason}"
        if (key == publishedKey) return null
        bubbleOpen = true
        publishedThisWindow = true
        publishedKey = key
        return opp
    }

    /** The bubble left the screen without a trade. The same setup is not published again. */
    fun onBubbleTimeout() {
        bubbleOpen = false
    }

    /**
     * The user locked the published signal. The observation window restarts now.
     * The caller keeps the trade's 60-second target on its own clock.
     */
    fun onUserLocked(nowMs: Long) {
        start(nowMs)
    }

    /** The maximum window ended. Returns whether a signal was already published. Does not invent one. */
    fun onMaximumWindow(nowMs: Long): Boolean {
        val already = publishedThisWindow
        start(nowMs)
        return already
    }
}

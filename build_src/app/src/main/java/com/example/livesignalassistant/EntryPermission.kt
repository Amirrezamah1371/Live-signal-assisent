package com.example.livesignalassistant

import kotlin.math.abs

/**
 * T+60 entry permission, separate from the dominant-wave side.
 * A correct UP or DOWN wave is not by itself permission to enter that side now.
 *
 * Empty string means this layer does not refuse. Reasons use bars that already exist:
 * half the prior swing (the structural-break retrace), the 3-second slope window,
 * and the analyzer late-entry exhaustion of 0.62. Nothing here is fit to a WIN/LOSS label.
 */
object EntryPermission {
    const val VALID_UP = "VALID_UP_ENTRY"
    const val VALID_DOWN = "VALID_DOWN_ENTRY"
    const val WAIT_EXHAUSTED = "WAIT_EXHAUSTED"
    const val WAIT_PULLBACK = "WAIT_PULLBACK"
    const val WAIT_SHOCK = "WAIT_SHOCK"
    const val WAIT_BAD_ENTRY_LOCATION = "WAIT_BAD_ENTRY_LOCATION"
    const val WAIT_DIRECTION_UNSTABLE = "WAIT_DIRECTION_UNSTABLE"
    const val WAIT_STALE_READ = "WAIT_STALE_READ"

    /** The existing 3-second slope window. |velocity| / |acceleration| under this means the impulse dies inside it. */
    private const val SPEED_WINDOW_SEC = 3.0

    /** Half the prior swing. The same retrace that confirms a structural break. */
    private const val SPENT_RETRACE = 0.5

    /** Analyzer late-entry exhaustion. Not lowered to catch more historical losses. */
    private const val LATE_EXHAUSTION = 0.62

    class Read(
        val sign: Int,
        val wave: WaveRead,
        val seriesOk: Boolean,
        val velocity: Double,
        val acceleration: Double,
        val stepScale: Double,
        val rangePosition: Double?,
        val exhaustion: Double
    )

    fun structureName(wave: WaveRead): String = when {
        !wave.valid -> "NO_STRUCTURE"
        wave.shock -> "SHOCK"
        wave.phase == "PULLBACK" -> "PULLBACK"
        wave.phase == "REVERSAL" -> "REVERSAL"
        wave.phase == "UNSTABLE" -> "UNSTABLE"
        wave.dominant > 0 -> "UP"
        wave.dominant < 0 -> "DOWN"
        else -> "NO_STRUCTURE"
    }

    fun permissionName(sign: Int): String = if (sign > 0) VALID_UP else if (sign < 0) VALID_DOWN else "WAIT"

    /**
     * Publication-time entry check. Call it only after [MarketStructure.block] has allowed the side.
     * It does not flip the side.
     */
    fun publication(
        sign: Int,
        wave: WaveRead,
        velocity: Double,
        acceleration: Double,
        stepScale: Double,
        rangePosition: Double?,
        exhaustion: Double
    ): String {
        if (sign == 0 || !wave.valid) return ""
        if (spentContinuation(sign, wave)) return WAIT_EXHAUSTED
        if (collapsingImpulse(sign, wave, velocity, acceleration, stepScale)) return WAIT_EXHAUSTED
        if (beyondSwing(sign, rangePosition, exhaustion)) return WAIT_BAD_ENTRY_LOCATION
        return ""
    }

    /**
     * Lock-time read of the current registered state.
     * A 3-second dip inside an intact 6s/10s wave does not invalidate the entry.
     * [seriesOk] is the existing series-status check, whose tail bar is 2.5 seconds.
     */
    fun atLock(sign: Int, wave: WaveRead, seriesOk: Boolean): String {
        if (sign == 0 || !seriesOk || !wave.valid) return WAIT_STALE_READ
        if (wave.shock) return WAIT_SHOCK
        if (wave.dominant != 0 && wave.dominant != sign) return WAIT_DIRECTION_UNSTABLE
        // Both medium windows have turned. One short window is not this case.
        if (sign * wave.z6 < 0.0 && sign * wave.z10 < 0.0) return WAIT_PULLBACK
        if (spentContinuation(sign, wave)) return WAIT_EXHAUSTED
        return ""
    }

    /** Structural lock check, then the same publication rules on the fresh read. */
    fun atEntry(read: Read): String {
        val structural = atLock(read.sign, read.wave, read.seriesOk)
        if (structural.isNotEmpty()) return structural
        return publication(
            read.sign, read.wave, read.velocity, read.acceleration, read.stepScale,
            read.rangePosition, read.exhaustion
        )
    }

    private fun spentContinuation(sign: Int, wave: WaveRead): Boolean =
        wave.dominant == sign && !wave.broken && wave.retrace >= SPENT_RETRACE

    private fun collapsingImpulse(
        sign: Int,
        wave: WaveRead,
        velocity: Double,
        acceleration: Double,
        stepScale: Double
    ): Boolean {
        val signedVel = sign * velocity
        val signedAcc = sign * acceleration
        if (signedVel <= 0.0 || signedAcc >= 0.0) return false
        if (abs(velocity) >= abs(acceleration) * SPEED_WINDOW_SEC) return false
        // A fast impulse can become the typical step, so stepScale alone does not see it.
        // 0.5 is the existing short-window support bar.
        return signedVel > stepScale || sign * wave.z3 >= 0.5
    }

    /** Price is outside the recent swing in the trade direction, and exhaustion is already late. */
    private fun beyondSwing(sign: Int, rangePosition: Double?, exhaustion: Double): Boolean {
        if (rangePosition == null || rangePosition.isNaN() || exhaustion.isNaN()) return false
        if (exhaustion < LATE_EXHAUSTION) return false
        return (sign > 0 && rangePosition > 1.0) || (sign < 0 && rangePosition < 0.0)
    }
}

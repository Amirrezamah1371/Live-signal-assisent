package com.example.livesignalassistant

import kotlin.math.sqrt

/**
 * Pure Experience V3 math. The file store delegates here so the same rules can be tested
 * without Android. BOOST is intentionally absent: the posterior is not a calibrated win
 * probability, and the attached store's only large cells are NO_RECENT.
 */
object ExperienceMath {
    private const val priorStrength = 10.0
    private const val minEffForEffect = 10.0

    fun levelKeys(action: String, state: String, regime: String, band: String) = listOf(
        action, "$action|$state", "$action|$state|$regime", "$action|$state|$regime|$band"
    )

    fun chain(m: Map<String, DoubleArray>, keys: List<String>): Triple<Double, Double, Int> {
        val c = Array(4) { m[keys[it]] ?: doubleArrayOf(0.0, 0.0) }
        val w0 = (c[0][0] - c[1][0]).coerceAtLeast(0.0)
        val l0 = (c[0][1] - c[1][1]).coerceAtLeast(0.0)
        var prior = (w0 + priorStrength) / (w0 + l0 + 2.0 * priorStrength)
        var alpha = priorStrength * prior
        var beta = priorStrength * (1.0 - prior)
        var deepest = if (c[0][0] + c[0][1] > 0) 0 else -1
        for (lv in 1..3) {
            val own = c[lv]
            alpha = own[0] + priorStrength * prior
            beta = own[1] + priorStrength * (1.0 - prior)
            if (own[0] + own[1] > 0) deepest = lv
            if (lv < 3) {
                val child = c[lv + 1]
                val rw = (own[0] - child[0]).coerceAtLeast(0.0)
                val rl = (own[1] - child[1]).coerceAtLeast(0.0)
                prior = (rw + priorStrength * prior) / (rw + rl + priorStrength)
            }
        }
        return Triple(alpha, beta, deepest)
    }

    /** Returns true when this id was already applied. Empty ids are never treated as duplicates. */
    fun alreadyApplied(ids: List<String>, signalId: String): Boolean =
        signalId.isNotEmpty() && signalId in ids

    fun advise(m: Map<String, DoubleArray>, action: String, state: String, regime: String, band: String): ExperienceStore.Advice {
        if (action != "UP" && action != "DOWN")
            return ExperienceStore.Advice("NONE", 0.0, 0.0, 0.5, 0.0, 1.0, -1, "", "NO_SIDE", 0.5)
        val keys = levelKeys(action, state, regime, band)
        val (alpha, beta, deepest) = chain(m, keys)
        val cell = m[keys[3]] ?: doubleArrayOf(0.0, 0.0)
        val nEff = cell[0] + cell[1]
        val p = alpha / (alpha + beta)
        val sd = sqrt(p * (1.0 - p) / (alpha + beta + 1.0))
        val lb = p - 1.2816 * sd
        val ub = p + 1.2816 * sd
        val other = if (action == "UP") "DOWN" else "UP"
        val (a2, b2, _) = chain(m, levelKeys(other, state, regime, band))
        val alt = a2 / (a2 + b2)
        val key = keys[3]
        if (nEff < minEffForEffect)
            return ExperienceStore.Advice("NONE", 0.0, nEff, p, lb, ub, deepest, key, "SMALL_SAMPLE", alt)
        if (ub < 0.50)
            return ExperienceStore.Advice("AVOID", -100.0, nEff, p, lb, ub, deepest, key, "UPPER80_BELOW_50", alt)
        if (ub < 0.55 && nEff >= 20.0) {
            val delta = -((0.55 - p) * 100.0).coerceIn(3.0, 15.0)
            return ExperienceStore.Advice("DAMP", delta, nEff, p, lb, ub, deepest, key, "UPPER80_BELOW_55", alt)
        }
        // A lower bound above 0.55 used to BOOST strength. That adds an uncalibrated rate onto STR.
        if (lb > 0.55)
            return ExperienceStore.Advice("NONE", 0.0, nEff, p, lb, ub, deepest, key, "UNCALIBRATED_NO_BOOST", alt)
        return ExperienceStore.Advice("NONE", 0.0, nEff, p, lb, ub, deepest, key, "NEUTRAL", alt)
    }

    /** Contexts that must not move the Beta weights. The outcome is still auditable. */
    fun trainsWeights(state: String): Boolean = state != "NO_RECENT" && state != "INSUFFICIENT"

    fun auditJson(
        tsMs: Long, signalId: String, action: String, state: String, regime: String, band: String,
        result: String, trained: Boolean, extra: Map<String, String>
    ): String {
        val sb = StringBuilder(256)
        sb.append("{\"ts_ms\":").append(tsMs)
        sb.append(",\"signal_id\":\"").append(escape(signalId)).append('"')
        sb.append(",\"action\":\"").append(escape(action)).append('"')
        sb.append(",\"state\":\"").append(escape(state)).append('"')
        sb.append(",\"regime\":\"").append(escape(regime)).append('"')
        sb.append(",\"band\":\"").append(escape(band)).append('"')
        sb.append(",\"result\":\"").append(escape(result)).append('"')
        sb.append(",\"trained\":").append(if (trained) "true" else "false")
        for ((k, v) in extra.entries.sortedBy { it.key }) {
            sb.append(",\"").append(escape(k)).append("\":\"").append(escape(v)).append('"')
        }
        sb.append('}')
        return sb.toString()
    }

    private fun escape(s: String): String = buildString(s.length + 8) {
        for (ch in s) when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
}

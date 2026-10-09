package com.example.livesignalassistant

import android.content.Context

/**
 * Manual session label and the chart-timeframe experiment tag.
 * Decision code does not read either value. They exist so a later Demo-vs-Real
 * or 1M-vs-5M comparison can keep exports from being mixed.
 */
object AccountMode {
    const val PREF = "lsa_session_label"
    const val KEY = "account_mode"

    fun current(ctx: Context): String {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "UNSPECIFIED")
        return normalizeMode(raw)
    }

    fun cycle(current: String): String = when (normalizeMode(current)) {
        "UNSPECIFIED" -> "DEMO"
        "DEMO" -> "REAL"
        else -> "UNSPECIFIED"
    }

    fun normalizeMode(raw: String?): String = when (raw) {
        "DEMO", "REAL" -> raw
        else -> "UNSPECIFIED"
    }
}

object ExperimentTag {
    const val PREDICTION_HORIZON_S = "60"
    const val OBSERVATION_MAX_S = "90"

    fun sessionFields(timeframe: String, accountMode: String): Map<String, String> = linkedMapOf(
        "TIMEFRAME" to normalizeTimeframe(timeframe),
        "account_mode" to AccountMode.normalizeMode(accountMode),
        "prediction_horizon_s" to PREDICTION_HORIZON_S,
        "observation_max_s" to OBSERVATION_MAX_S
    )

    fun normalizeTimeframe(raw: String?): String = when (raw) {
        "1M", "5M" -> raw
        else -> "UNSPECIFIED"
    }

    fun readme(timeframe: String, accountMode: String): String {
        val tags = sessionFields(timeframe, accountMode)
        return "Live Signal Assistant memory export.\n" +
            "TIMEFRAME=${tags["TIMEFRAME"]}\n" +
            "account_mode=${tags["account_mode"]}\n" +
            "prediction_horizon_s=${tags["prediction_horizon_s"]}\n" +
            "observation_max_s=${tags["observation_max_s"]}\n" +
            "EV is direction evidence and is NOT a calibrated win probability.\n" +
            "account_mode is a manual label for later comparison. It is not an input to the decision.\n" +
            "The chart timeframe is the only difference between the 1M and 5M experiment builds.\n" +
            "Both builds predict the same 60-second horizon.\n" +
            "Permanent Experience Core is NEVER cleared by session cleanup.\n"
    }
}

package com.example.livesignalassistant

/** What CycleDecider needs from Experience. Tests can supply a fake. */
interface ExperienceSource {
    fun advise(action: String, state: String, regime: String, band: String): ExperienceStore.Advice
}

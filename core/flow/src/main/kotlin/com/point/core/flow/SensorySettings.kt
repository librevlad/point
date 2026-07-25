package com.point.core.flow

/** The one sensory preference (MOTION.md M4): branded action sounds on/off. Haptics have
 *  no in-app switch — the system's touch-feedback setting governs them. Default is ON:
 *  the sound builds the product's recognisable association (владелец, коммент к #114). */
interface SensorySettings {
    fun isSoundEnabled(): Boolean
    suspend fun setSoundEnabled(enabled: Boolean)
}

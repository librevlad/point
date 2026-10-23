package com.point.core.flow

interface SensorySettings {
    fun isSoundEnabled(): Boolean
    suspend fun setSoundEnabled(enabled: Boolean)

    /** Сколько раз человек видел, как найденное летит в свою строку (#209, D7). */
    fun theatrePlayed(): Int = 0

    suspend fun countTheatrePlayed() {}
}

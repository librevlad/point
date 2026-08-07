package com.point.core.flow

interface SensorySettings {
    fun isSoundEnabled(): Boolean
    suspend fun setSoundEnabled(enabled: Boolean)
}

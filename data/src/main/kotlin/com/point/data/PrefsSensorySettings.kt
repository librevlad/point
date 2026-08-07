package com.point.data

import android.content.Context
import com.point.core.flow.SensorySettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrefsSensorySettings @Inject constructor(
    @ApplicationContext private val context: Context,
) : SensorySettings {

    private val prefs by lazy { context.getSharedPreferences("sensory", Context.MODE_PRIVATE) }

    override fun isSoundEnabled(): Boolean =
        runCatching { prefs.getBoolean(KEY_SOUND, true) }.getOrDefault(true)

    override suspend fun setSoundEnabled(enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY_SOUND, enabled).apply()
    }

    private companion object { const val KEY_SOUND = "sound_enabled" }
}

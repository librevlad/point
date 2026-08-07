package com.point.data

import android.content.Context
import com.point.core.flow.CloudPrivacySettings
import com.point.core.flow.PrivacyLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrefsCloudPrivacySettings @Inject constructor(
    @ApplicationContext private val context: Context,
) : CloudPrivacySettings {

    private val prefs by lazy { context.getSharedPreferences("privacy", Context.MODE_PRIVATE) }

    override fun level(): PrivacyLevel =
        runCatching { PrivacyLevel.of(prefs.getString(KEY_LEVEL, null)) }.getOrDefault(PrivacyLevel.DEFAULT)

    override suspend fun setLevel(level: PrivacyLevel): Unit = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_LEVEL, level.name).apply()
    }

    private companion object { const val KEY_LEVEL = "cloud_level" }
}

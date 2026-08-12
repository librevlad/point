package com.point.data

import android.content.Context
import com.point.core.flow.CloudPrivacySettings
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.YoloMode
import com.point.core.flow.privacyLevelIn
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrefsCloudPrivacySettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val yolo: YoloMode,
) : CloudPrivacySettings {

    private val prefs by lazy { context.getSharedPreferences("privacy", Context.MODE_PRIVATE) }

    /**
     * В режиме YOLO наружу открыты все пути (#795): человек попросил лучшее, а не бережное.
     * Выбранный прежде уровень при этом не стирается — выключите режим, и он вернётся.
     */
    override fun level(): PrivacyLevel = privacyLevelIn(
        yolo = runCatching { yolo.enabled() }.getOrDefault(false),
        chosen = runCatching { PrivacyLevel.of(prefs.getString(KEY_LEVEL, null)) }
            .getOrDefault(PrivacyLevel.DEFAULT),
    )

    override suspend fun setLevel(level: PrivacyLevel): Unit = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_LEVEL, level.name).apply()
    }

    private companion object { const val KEY_LEVEL = "cloud_level" }
}

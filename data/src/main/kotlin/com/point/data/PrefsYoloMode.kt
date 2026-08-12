package com.point.data

import android.content.Context
import androidx.core.content.edit
import com.point.core.flow.YoloMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Каким режим приходит в сборке, где его ещё не трогали руками (#795).
 *
 * Значение даёт `:app` — только он знает, какая это сборка. `:data` про типы сборок не знает
 * и знать не должен.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class YoloByDefault

@Singleton
class PrefsYoloMode @Inject constructor(
    @ApplicationContext context: Context,
    @YoloByDefault private val byDefault: Boolean,
) : YoloMode {

    private val prefs = context.getSharedPreferences("point_privacy", Context.MODE_PRIVATE)

    override fun enabled(): Boolean =
        runCatching { prefs.getBoolean(KEY, byDefault) }.getOrDefault(byDefault)

    override suspend fun setEnabled(enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        prefs.edit { putBoolean(KEY, enabled) }
    }

    private companion object { const val KEY = "yolo" }
}

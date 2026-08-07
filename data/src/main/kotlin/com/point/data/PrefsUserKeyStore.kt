package com.point.data

import android.content.Context
import androidx.core.content.edit
import com.point.core.flow.UserAiConfig
import com.point.core.flow.UserKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class PrefsUserKeyStore @Inject constructor(
    @ApplicationContext context: Context,
) : UserKeyStore {

    private val prefs = context.getSharedPreferences("point_ai", Context.MODE_PRIVATE)

    override fun read(): UserAiConfig? {
        val key = prefs.getString(KEY, "").orEmpty()
        if (key.isBlank()) return null
        return UserAiConfig(
            apiKey = key,
            baseUrl = prefs.getString(BASE_URL, null).orEmpty().ifBlank { UserAiConfig.DEFAULT.baseUrl },
            model = prefs.getString(MODEL, null).orEmpty().ifBlank { UserAiConfig.DEFAULT.model },
        )
    }

    override suspend fun save(config: UserAiConfig) = withContext(Dispatchers.IO) {
        prefs.edit {
            putString(KEY, config.apiKey.trim())
            putString(BASE_URL, config.baseUrl.trim().ifBlank { UserAiConfig.DEFAULT.baseUrl })
            putString(MODEL, config.model.trim().ifBlank { UserAiConfig.DEFAULT.model })
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) { prefs.edit { clear() } }

    private companion object {
        const val KEY = "api_key"
        const val BASE_URL = "base_url"
        const val MODEL = "model"
    }
}

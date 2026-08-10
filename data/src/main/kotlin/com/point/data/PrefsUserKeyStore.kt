package com.point.data

import android.content.Context
import androidx.core.content.edit
import com.point.core.flow.UserAiConfig
import com.point.core.flow.UserAiKey
import com.point.core.flow.UserAiKeys
import com.point.core.flow.UserKeyStore
import com.point.core.flow.decodeUserAiKeys
import com.point.core.flow.encodeUserAiKeys
import com.point.core.flow.keysFromSingleKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class PrefsUserKeyStore @Inject constructor(
    @ApplicationContext context: Context,
) : UserKeyStore {

    private val prefs = context.getSharedPreferences("point_ai", Context.MODE_PRIVATE)

    override fun keys(): UserAiKeys {
        val stored = prefs.getString(KEYS, null)
        if (stored != null) return decodeUserAiKeys(stored)
        return moveOldKey()
    }

    /**
     * Единственный ключ старой схемы переезжает к своему сервису при первом же
     * чтении: обновление приложения не должно стоить человеку его ключа (#699).
     */
    private fun moveOldKey(): UserAiKeys {
        val old = prefs.getString(KEY, "").orEmpty()
        if (old.isBlank()) return UserAiKeys.NONE
        val moved = keysFromSingleKey(
            UserAiConfig(
                apiKey = old,
                baseUrl = prefs.getString(BASE_URL, null).orEmpty().ifBlank { UserAiConfig.DEFAULT.baseUrl },
                model = prefs.getString(MODEL, null).orEmpty().ifBlank { UserAiConfig.DEFAULT.model },
                savedAt = prefs.getLong(SAVED_AT, 0L),
            ),
        )
        prefs.edit {
            putString(KEYS, encodeUserAiKeys(moved))
            remove(KEY)
            remove(BASE_URL)
            remove(MODEL)
        }
        return moved
    }

    override suspend fun save(key: UserAiKey) = withContext(Dispatchers.IO) {
        write(keys().with(key))
    }

    override suspend fun forget(providerId: String) = withContext(Dispatchers.IO) {
        write(keys().without(providerId))
    }

    override suspend fun clear() = withContext(Dispatchers.IO) { prefs.edit { clear() } }

    private fun write(keys: UserAiKeys) {
        prefs.edit { putString(KEYS, encodeUserAiKeys(keys)) }
    }

    private companion object {
        const val KEYS = "keys"

        const val KEY = "api_key"
        const val BASE_URL = "base_url"
        const val MODEL = "model"
        const val SAVED_AT = "saved_at"
    }
}

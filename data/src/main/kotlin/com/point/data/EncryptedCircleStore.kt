package com.point.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.point.core.flow.CircleDevice
import com.point.core.flow.CircleStore
import com.point.core.flow.DeviceKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * Последний успешный круг устройств — тем же механизмом, что пропуск (#1076).
 *
 * Хранилище своё, а не файл аккаунта: у пропуска и у круга разные жизни записи,
 * но конец один — выход из аккаунта стирает оба, чужие устройства не переживают его.
 */
class EncryptedCircleStore internal constructor(
    prefsSource: () -> SharedPreferences?,
) : CircleStore {

    @Inject constructor(@ApplicationContext context: Context) : this({ encryptedCirclePrefs(context) })

    private val prefs: SharedPreferences? by lazy(prefsSource)

    override fun current(): List<CircleDevice>? {
        val raw = prefs?.getString(DEVICES, null) ?: return null
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                CircleDevice(
                    id = o.getString(ID),
                    kind = if (o.optString(KIND) == PC) DeviceKind.PC else DeviceKind.PHONE,
                    name = o.optString(NAME),
                    lastSeenMillis = o.optLong(LAST_SEEN, -1L).takeIf { it >= 0 },
                    self = o.optBoolean(SELF),
                )
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    override suspend fun save(devices: List<CircleDevice>): Unit = withContext(Dispatchers.IO) {

        // Пустой список — незнание круга, а не круг без устройств (контракт [CircleStore]).
        if (devices.isEmpty()) return@withContext
        val array = JSONArray()
        devices.forEach { device ->
            array.put(
                JSONObject()
                    .put(ID, device.id)
                    .put(KIND, if (device.kind == DeviceKind.PC) PC else PHONE)
                    .put(NAME, device.name)
                    .apply { device.lastSeenMillis?.let { put(LAST_SEEN, it) } }
                    .put(SELF, device.self),
            )
        }
        prefs?.edit { putString(DEVICES, array.toString()) }
        Unit
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        prefs?.edit { clear() }
        Unit
    }

    private companion object {
        const val DEVICES = "devices"
        const val ID = "id"
        const val KIND = "kind"
        const val NAME = "name"
        const val LAST_SEEN = "last_seen"
        const val SELF = "self"
        const val PC = "pc"
        const val PHONE = "phone"
    }
}

/** Шифрованные prefs круга; не удалось создать — кэша просто нет, круг живёт как раньше. */
private fun encryptedCirclePrefs(context: Context): SharedPreferences? = runCatching {
    EncryptedSharedPreferences.create(
        context,
        "point_circle",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}.getOrNull()

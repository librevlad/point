package com.point.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.point.core.flow.DeviceKeyPair
import com.point.core.flow.DeviceKeyStore
import com.point.core.flow.DeviceKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ключи этого телефона (#475) — там же, где пропуск аккаунта, и по той же причине.
 *
 * Закрытая половина — единственное, чем читается всё, что приезжает с компьютера. Планка та же,
 * что у пропуска ([EncryptedAccountStore]): ключ шифрования держит Android Keystore, за пределы
 * устройства он не выходит, и резервная копия с ним ничего не даёт.
 *
 * Пара рождается один раз и живёт, пока живёт установка. Пережить «Выйти» она обязана: сменившийся
 * ключ означал бы, что круг знает про телефон неправду, и всё, что для него уже положили в ящик,
 * стало бы нечитаемым.
 *
 * Хранилище не открылось (Keystore на некоторых прошивках портится после обновления) — пара
 * рождается заново в памяти: связь с компьютером не заработает до следующего входа, зато Point не
 * ляжет молча.
 */
@Singleton
class EncryptedDeviceKeys @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceKeyStore {

    private val prefs: SharedPreferences? by lazy {
        runCatching {
            EncryptedSharedPreferences.create(
                context,
                "point_device_keys",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
    }

    @Volatile
    private var cache: DeviceKeyPair? = null

    @Synchronized
    override fun keys(): DeviceKeyPair {
        cache?.let { return it }
        val store = prefs
        val stored = store?.let { p ->
            val secret = p.getString(PRIVATE, null)?.takeIf { it.isNotBlank() }
            val public = p.getString(PUBLIC, null)?.takeIf { it.isNotBlank() }
            if (secret != null && public != null) DeviceKeyPair(secret, public) else null
        }
        val pair = stored ?: DeviceKeys.generate().also { fresh ->
            store?.edit { putString(PRIVATE, fresh.privateKey); putString(PUBLIC, fresh.publicKey) }
        }
        cache = pair
        return pair
    }

    private companion object {
        const val PRIVATE = "device_private"
        const val PUBLIC = "device_public"
    }
}

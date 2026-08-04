package com.point.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.point.core.flow.AccountStore
import com.point.core.flow.DeviceKind
import com.point.core.flow.PointAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Пропуск аккаунта на телефоне (#472) — в [EncryptedSharedPreferences].
 *
 * Почему планка выше, чем у ключа AI (`PrefsUserKeyStore`, обычные app-private prefs): тот ключ
 * чужой и отзывается у провайдера, а этот — долгоживущий доступ к **своим объектам**, и живёт он
 * годами. Ключ шифрования держит Android Keystore, то есть за пределы устройства он не выходит
 * вовсе; резервная копия с ним ничего не даёт.
 *
 * Если хранилище не открылось (Keystore на некоторых прошивках умеет портиться после обновления),
 * пропуск считается отсутствующим — человек увидит вход, а не молчаливо сломанный Point.
 */
class EncryptedAccountStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : AccountStore {

    private val prefs: SharedPreferences? by lazy {
        runCatching {
            EncryptedSharedPreferences.create(
                context,
                "point_account",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
    }

    override fun current(): PointAccount? {
        val p = prefs ?: return null
        val id = p.getString(DEVICE_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val token = p.getString(DEVICE_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        return PointAccount(
            deviceId = id,
            deviceToken = token,
            email = p.getString(EMAIL, null).orEmpty(),
            deviceName = p.getString(NAME, null).orEmpty(),
            kind = DeviceKind.PHONE,
        )
    }

    override suspend fun save(account: PointAccount) = withContext(Dispatchers.IO) {
        prefs?.edit {
            putString(DEVICE_ID, account.deviceId)
            putString(DEVICE_TOKEN, account.deviceToken)
            putString(EMAIL, account.email)
            putString(NAME, account.deviceName)
        }
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs?.edit { clear() }
        Unit
    }

    private companion object {
        const val DEVICE_ID = "device_id"
        const val DEVICE_TOKEN = "device_token"
        const val EMAIL = "email"
        const val NAME = "name"
    }
}

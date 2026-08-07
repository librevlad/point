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

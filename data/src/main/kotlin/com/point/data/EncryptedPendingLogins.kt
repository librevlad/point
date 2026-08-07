package com.point.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.point.core.flow.PendingLogin
import com.point.core.flow.PendingLoginStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class EncryptedPendingLogins @Inject constructor(
    @ApplicationContext private val context: Context,
) : PendingLoginStore {

    private val prefs: SharedPreferences? by lazy {
        runCatching {
            EncryptedSharedPreferences.create(
                context,
                "point_pending_login",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
    }

    override fun current(): PendingLogin? {
        val p = prefs ?: return null
        val id = p.getString(LOGIN_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val claim = p.getString(CLAIM, null)?.takeIf { it.isNotBlank() } ?: return null
        return PendingLogin(
            loginId = id,
            claimToken = claim,
            code = p.getString(CODE, null).orEmpty(),
            url = p.getString(URL, null).orEmpty(),
            startedAtMillis = p.getLong(STARTED_AT, 0L),
        )
    }

    override suspend fun save(login: PendingLogin) = withContext(Dispatchers.IO) {
        prefs?.edit {
            putString(LOGIN_ID, login.loginId)
            putString(CLAIM, login.claimToken)
            putString(CODE, login.code)
            putString(URL, login.url)
            putLong(STARTED_AT, login.startedAtMillis)
        }
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs?.edit { clear() }
        Unit
    }

    private companion object {
        const val LOGIN_ID = "login_id"
        const val CLAIM = "claim_token"
        const val CODE = "user_code"
        const val URL = "login_url"
        const val STARTED_AT = "started_at"
    }
}

package com.point.data

import android.content.Context
import androidx.core.content.edit
import com.point.core.flow.CloudScope
import com.point.core.flow.PrivacyConsent
import com.point.core.flow.remembersConsent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrefsPrivacyConsent @Inject constructor(
    @ApplicationContext context: Context,
) : PrivacyConsent {

    private val prefs = context.getSharedPreferences("point_privacy", Context.MODE_PRIVATE)

    override suspend fun allowed(scope: CloudScope): Boolean = withContext(Dispatchers.IO) {
        remembersConsent(scope) && prefs.getBoolean(CLOUD, false)
    }

    override suspend fun allow(scope: CloudScope) = withContext(Dispatchers.IO) {
        if (remembersConsent(scope)) prefs.edit { putBoolean(CLOUD, true) } else Unit
    }

    override suspend fun revoke(scope: CloudScope) = withContext(Dispatchers.IO) {
        if (remembersConsent(scope)) prefs.edit { remove(CLOUD) } else Unit
    }

    private companion object {
        const val CLOUD = "cloud_allowed"
    }
}

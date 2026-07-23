package com.point.data

import android.content.Context
import androidx.core.content.edit
import com.point.core.flow.PrivacyConsent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Cloud consent as an app-private flag. Off until the user agrees once. */
@Singleton
class PrefsPrivacyConsent @Inject constructor(
    @ApplicationContext context: Context,
) : PrivacyConsent {

    private val prefs = context.getSharedPreferences("point_privacy", Context.MODE_PRIVATE)

    override suspend fun cloudAllowed(): Boolean = withContext(Dispatchers.IO) { prefs.getBoolean(CLOUD, false) }

    override suspend fun allowCloud() = withContext(Dispatchers.IO) { prefs.edit { putBoolean(CLOUD, true) } }

    private companion object {
        const val CLOUD = "cloud_allowed"
    }
}

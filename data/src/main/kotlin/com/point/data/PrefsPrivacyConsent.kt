package com.point.data

import android.content.Context
import androidx.core.content.edit
import com.point.core.flow.CloudScope
import com.point.core.flow.PrivacyConsent
import com.point.core.flow.YoloMode
import com.point.core.flow.cloudAllowedIn
import com.point.core.flow.remembersConsent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrefsPrivacyConsent @Inject constructor(
    @ApplicationContext context: Context,
    private val yolo: YoloMode,
) : PrivacyConsent {

    private val prefs = context.getSharedPreferences("point_privacy", Context.MODE_PRIVATE)

    /**
     * Режим YOLO и есть заранее данное согласие на чтение моделями (#795): человек выбрал
     * режим — вопрос перед каждым действием ему уже задан.
     *
     * Открытая ссылка так не открывается: `PUBLIC_LINK` согласия вообще не запоминает
     * (`remembersConsent`), потому что выкладывание файла наружу — необратимое действие, а
     * не уровень доверия. Про такое спрашивают каждый раз, в любом режиме.
     */
    override suspend fun allowed(scope: CloudScope): Boolean = withContext(Dispatchers.IO) {
        cloudAllowedIn(
            scope = scope,
            yolo = runCatching { yolo.enabled() }.getOrDefault(false),
            remembered = prefs.getBoolean(CLOUD, false),
        )
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

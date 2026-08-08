package com.point

import android.content.Context
import android.content.pm.PackageManager
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import com.point.core.flow.SensorySettings
import com.point.data.VibratorSensoryFeedback
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Этап 10, F1/F2: сенсорный отклик должен быть живым, а не молча проглоченным.
 * Разрешение VIBRATE заявлено манифестом, и вибрация реально доходит до мотора —
 * и на Android 8 (до готовых системных образцов), и после.
 */
@RunWith(RobolectricTestRunner::class)
class SensoryFeedbackTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val silentSettings = object : SensorySettings {
        override fun isSoundEnabled() = false
        override suspend fun setSoundEnabled(enabled: Boolean) = Unit
    }

    private fun vibratorShadow() =
        shadowOf(context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)

    @Test fun `приложение заявляет право на вибрацию`() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()

        assertTrue(android.Manifest.permission.VIBRATE in requested)
    }

    @Config(sdk = [26])
    @Test fun `на Android 8 отклик доходит до мотора, а не гаснет молча`() {
        VibratorSensoryFeedback(context, silentSettings).success()

        assertTrue(vibratorShadow().isVibrating)
    }

    @Config(sdk = [26])
    @Test fun `неудача на Android 8 — двойной импульс`() {
        VibratorSensoryFeedback(context, silentSettings).failure()

        assertTrue(vibratorShadow().isVibrating)
    }

    @Config(sdk = [30])
    @Test fun `после Android 10 — системный образец`() {
        VibratorSensoryFeedback(context, silentSettings).tap()

        assertTrue(vibratorShadow().isVibrating)
    }
}

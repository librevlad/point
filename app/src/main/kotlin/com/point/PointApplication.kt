package com.point

import android.app.Application
import com.point.core.flow.CrashLog
import com.point.core.flow.formatCrashReport
import com.point.data.RemovedUsageJournal
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PointApplication : Application() {

    @Inject lateinit var crashLog: CrashLog

    @Inject lateinit var removedUsageJournal: RemovedUsageJournal

    override fun onCreate() {
        super.onCreate()
        tellPhoneRegion()
        warmUpScanPack()
        eraseRemovedUsageJournal()
        val system = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val version = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull() ?: "?"
                crashLog.record(formatCrashReport(version, thread.name, error))
            }
            system?.uncaughtException(thread, error)
        }
    }

    /**
     * Откуда Point знает страну номера без «+» (#801).
     *
     * `067 636 05 60` — украинский мобильный, а в другой стране это другой номер или не
     * номер вовсе. Подсказку даёт SIM, а если её нет — язык устройства. Само правило живёт
     * в `:core:flow`, и Android-у оно ничего не должно: сюда приходит только код страны.
     */
    private fun tellPhoneRegion() {
        val fromSim = runCatching {
            (getSystemService(TELEPHONY_SERVICE) as? android.telephony.TelephonyManager)
                ?.networkCountryIso?.takeIf { it.isNotBlank() }
        }.getOrNull()
        val fromLocale = runCatching {
            resources.configuration.locales[0].country.takeIf { it.isNotBlank() }
        }.getOrNull()
        (fromSim ?: fromLocale)?.let {
            com.point.core.flow.PhoneNumbers.region = it.uppercase()
        }
    }

    /** Журнал убранной «Приватной статистики» стирается при обновлении (#579). */
    private fun eraseRemovedUsageJournal() {
        Thread { runCatching { removedUsageJournal.erase() } }
            .apply { isDaemon = true }
            .start()
    }

    private fun warmUpScanPack() {
        Thread { runCatching { com.point.executors.OpenCvScan.available } }
            .apply { isDaemon = true }
            .start()
    }
}

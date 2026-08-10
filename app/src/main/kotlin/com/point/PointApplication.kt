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

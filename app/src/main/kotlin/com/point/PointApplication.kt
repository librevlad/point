package com.point

import android.app.Application
import com.point.core.flow.CrashLog
import com.point.core.flow.formatCrashReport
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PointApplication : Application() {

    @Inject lateinit var crashLog: CrashLog

    override fun onCreate() {
        super.onCreate()
        warmUpScanPack()
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

    private fun warmUpScanPack() {
        Thread { runCatching { com.point.executors.OpenCvScan.available } }
            .apply { isDaemon = true }
            .start()
    }
}

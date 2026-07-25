package com.point

import android.app.Application
import com.point.core.flow.CrashLog
import com.point.core.flow.formatCrashReport
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point and Hilt DI root.
 *
 * App-scoped bindings — the ExecutorRegistry, the scratch ObjectStore and the
 * Gemini LlmClient — are provided through Hilt and injected. They are DI
 * instances, NOT global/static singletons or Kotlin `object`s (see the
 * "Никаких синглтонов" clarification in the spec and docs/DECISIONS.md).
 *
 * Crash visibility (#11): the default exception handler journals the report to a local
 * file (privacy-first — no crash SDK), then defers to the system handler so the process
 * dies honestly. The next start offers the report for an explicit share.
 */
@HiltAndroidApp
class PointApplication : Application() {

    @Inject lateinit var crashLog: CrashLog

    override fun onCreate() {
        super.onCreate()
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
}

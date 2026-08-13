package com.point

import android.app.Application
import com.point.core.flow.CrashLog
import com.point.core.flow.formatCrashReport
import com.point.data.RemovedUsageJournal
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltAndroidApp
class PointApplication : Application() {

    @Inject lateinit var crashLog: CrashLog

    @Inject lateinit var removedUsageJournal: RemovedUsageJournal

    @Inject lateinit var accounts: com.point.core.flow.AccountStore

    @Inject lateinit var accountClient: com.point.core.flow.AccountClient

    override fun onCreate() {
        super.onCreate()
        val started = android.os.SystemClock.uptimeMillis()

        // На главном потоке остаётся только то, без чего не нарисовать первый экран (#944).
        // Живая охота 13.08.2026 поймала `ANR in com.point · failed to complete startup`:
        // в `onCreate` копилось всё, что кому-то однажды понадобилось на старте, и стоимость
        // этого никто не считал. Конституция требует первый экран за 300 мс и без I/O.
        tellPhoneRegion()
        warmUpScanPack()
        eraseRemovedUsageJournal()
        offMainThread { tellWhereToKnock() }
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
        tellHowLongStartupTook(started)
    }

    /**
     * Сказать серверу, куда стучать в этот телефон (#817).
     *
     * Адрес выдаёт Google и меняет, когда захочет, — помнить его нельзя, надо спрашивать.
     * Без настроек Firebase спрашивать не у кого: тогда стука просто нет, и просьба
     * компьютера разбирается, когда человек откроет Point сам.
     */
    private fun tellWhereToKnock() {
        val account = accounts.current() ?: return
        val app = runCatching { com.google.firebase.FirebaseApp.getApps(this) }.getOrNull()
        if (app.isNullOrEmpty()) return

        runCatching {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { address ->
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        runCatching { accountClient.tellPushAddress(account, address) }
                    }
                }
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
        offMainThread { removedUsageJournal.erase() }
    }

    private fun warmUpScanPack() {
        offMainThread { com.point.executors.OpenCvScan.available }
    }

    private fun offMainThread(work: () -> Unit) {
        Thread { runCatching { work() } }.apply { isDaemon = true }.start()
    }

    /**
     * Сколько занял запуск.
     *
     * У нормы «первый экран за 300 мс без I/O» не было числа, и её нечем было проверить:
     * работа в `onCreate` копилась, пока система не сказала «failed to complete startup».
     * Теперь у неё есть число, и оно видно в журнале устройства.
     */
    private fun tellHowLongStartupTook(startedAt: Long) {
        val took = android.os.SystemClock.uptimeMillis() - startedAt
        val how = if (took > SLOW_STARTUP_MS) android.util.Log.WARN else android.util.Log.INFO
        android.util.Log.println(how, "PointStart", "запуск занял $took мс")
    }

    private companion object {
        /** Дольше этого запуск уже мешает человеку, и это стоит увидеть в журнале. */
        const val SLOW_STARTUP_MS = 300
    }
}

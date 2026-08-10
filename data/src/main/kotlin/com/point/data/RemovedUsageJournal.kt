package com.point.data

import com.point.data.di.UsageDir
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Настройка «Приватная статистика» и журнал за ней убраны (#579).
 *
 * Записи, сделанные прежними версиями, стираются при первом запуске новой:
 * убранная настройка не должна оставлять на устройстве лежать то, что человек
 * больше не может ни увидеть, ни выключить.
 */
@Singleton
class RemovedUsageJournal @Inject constructor(
    @UsageDir private val baseDir: File,
) {

    fun erase() {
        runCatching {
            File(baseDir, "usage.jsonl").delete()
            File(baseDir, "consent").delete()
        }
    }
}

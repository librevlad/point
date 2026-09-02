package com.point.data

import android.content.Context
import com.point.core.flow.KnockTrace
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * След стука в файле телефона (#1398): `files/knock-log.txt`.
 *
 * Одна строка на событие, со временем. Файл не растёт без предела: когда строк становится
 * больше [KEEP], остаётся последняя половина. Читается по проводу тем же способом, что и
 * журнал обменов с моделью.
 */
class FileKnockTrace @Inject constructor(
    @ApplicationContext private val context: Context,
) : KnockTrace {

    private val file: File get() = File(context.filesDir, "knock-log.txt")

    @Synchronized
    override fun note(said: String) {
        runCatching {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
            file.appendText("$stamp  $said\n")
            val lines = file.readLines()
            if (lines.size > KEEP) file.writeText(lines.takeLast(KEEP / 2).joinToString("\n") + "\n")
        }
    }

    private companion object {
        const val KEEP = 300
    }
}

package com.point

import com.point.core.flow.SharedTexts
import java.io.File

/**
 * Расшаренный текст в тестах — во временной папке, которую тест может осмотреть.
 *
 * Проверять уборку счётчиком вызовов бессмысленно: обещание звучит как «файла на диске больше
 * нет», а не «метод позвали». Поэтому подделка пишет настоящие файлы — и тест смотрит на диск.
 */
class FakeSharedTexts(
    private val dir: File = File(System.getProperty("java.io.tmpdir"), "point-shared-" + System.nanoTime()),
) : SharedTexts {

    override fun create(text: String): String {
        dir.mkdirs()
        return File.createTempFile("shared-", ".txt", dir).apply { writeText(text) }.absolutePath
    }

    override fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Сколько текстов сейчас лежит на диске — то, что человек нашёл бы в папке приложения. */
    fun files(): List<File> = dir.listFiles()?.toList().orEmpty()
}

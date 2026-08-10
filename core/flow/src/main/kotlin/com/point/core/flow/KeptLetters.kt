package com.point.core.flow

import java.io.File

/**
 * Письма, которые уже лежат на диске и ждут разбора.
 *
 * Сервер держит письмо, пока приём не подтверждён, поэтому подтверждать раньше, чем
 * письмо сохранено, нельзя: падение между «скачал» и «сохранил» стоило человеку
 * объекта навсегда (#680). Сохранённое письмо подтверждается сразу — сервер
 * освобождается, повторных доставок не будет, — а разбор идёт отдельным шагом и
 * может повторяться сколько угодно.
 *
 * Письмо, разбор которого валит приложение раз за разом, после [tries] попыток
 * перестаёт ждать, но не стирается: объект человека остаётся на диске, а круг
 * падений прекращается.
 */
class KeptLetters(private val dir: File, val tries: Int = 3) {

    /** Сохранить письмо целиком или не сохранить вовсе — половина письма хуже, чем ничего. */
    @Synchronized
    fun keep(id: String, blob: ByteArray) {
        val name = nameOf(id)
        dir.mkdirs()
        val letter = File(dir, name + LETTER)

        // То же письмо привозят снова, когда подтверждение не дошло: счёт попыток
        // разбора при этом не начинается заново.
        if (letter.isFile) return
        val part = File(dir, name + PART)
        part.writeBytes(blob)
        if (!part.renameTo(letter)) {
            letter.delete()
            part.renameTo(letter)
        }
    }

    /** Письма в порядке прихода. Отложенные не в счёт. */
    @Synchronized
    fun waiting(): List<String> = dir.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(LETTER) }
        .map { it.name.removeSuffix(LETTER) }
        .filter { attempts(it) < tries }
        .sorted()

    @Synchronized
    fun blob(id: String): ByteArray? =
        File(dir, nameOf(id) + LETTER).takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }

    /**
     * Отметить начатую попытку разбора и вернуть её номер. Считается ДО разбора:
     * иначе падение, унёсшее приложение целиком, не было бы посчитано.
     */
    @Synchronized
    fun tried(id: String): Int {
        val next = attempts(id) + 1
        runCatching {
            dir.mkdirs()
            File(dir, nameOf(id) + TRIES).writeText(next.toString())
        }
        return next
    }

    /** Разобрано — письмо больше не ждёт. */
    @Synchronized
    fun done(id: String) {
        val name = nameOf(id)
        listOf(LETTER, TRIES).forEach { runCatching { File(dir, name + it).delete() } }
    }

    private fun attempts(id: String): Int =
        runCatching { File(dir, nameOf(id) + TRIES).readText().trim().toInt() }.getOrDefault(0)

    // Имя письма приходит с сервера — писать по нему куда попало нельзя.
    private fun nameOf(id: String): String =
        id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(MAX_NAME).ifBlank { "письмо" }

    private companion object {

        const val LETTER = ".bin"

        const val TRIES = ".tries"

        const val PART = ".part"

        const val MAX_NAME = 64
    }
}

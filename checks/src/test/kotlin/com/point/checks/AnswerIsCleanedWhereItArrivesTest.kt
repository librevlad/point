package com.point.checks

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ответ модели чистится там, где приходит, — одним местом на все действия (#1320).
 *
 * «Понять», «В Excel», Word+, чат и «Исправить ошибки» читают ответ уже файлом, поэтому всё,
 * что клиент в этот файл положит, человек прочтёт как ответ: ход мысли вслух думающей модели
 * уехал ему строками в лист Excel именно так. Чистить у каждого действия по-своему — значит
 * знать наизусть, кто из них уже научен: заплатку ставили бы там, где заметили, а следующее
 * действие получало бы ответ как есть.
 *
 * Клиентов трое, и завтра их четверо. Ручной список файлов десятого не поймал бы, поэтому
 * сторож смотрит на признак работы: кто кладёт ответ модели файлом, тот зовёт общую очистку.
 *
 * Живёт в `:checks` (#1293): клиенты живут в `:core:flow`, а ключи человека и внешние
 * реализации — в `:data`, и модуля, который собирал бы оба, в проекте нет.
 */
class AnswerIsCleanedWhereItArrivesTest {

    /** Признак работы: ответ модели ложится в свой файл именно так. */
    private val putsAnswerInFile = """newScratchFile("md")"""

    private fun sources(): List<File> =
        listOf("core/flow/src/main", "data/src/main", "desktop/src/main")
            .map { File(repo, it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }

    @Test
    fun `кто кладёт ответ модели файлом, тот зовёт общую очистку`() {
        val writers = sources().filter { code(it.readText()).contains(putsAnswerInFile) }

        assertTrue("ни одного клиента не нашлось — ответ кладут иначе?", writers.size >= 3)

        val guilty = writers
            .filterNot { code(it.readText()).contains("answerOnly(") }
            .map { it.name }

        assertTrue("ответ доходит до человека мимо общей очистки: $guilty", guilty.isEmpty())
    }
}

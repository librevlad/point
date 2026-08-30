package com.point.checks

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Запуск компьютера забывает брошенное (#1317, решение владельца 29.08.2026).
 *
 * Сама уборка живёт в `forgetAbandoned` и проверяется тестом `:desktop` запуском. Но зовёт её
 * `main()`, а `main()` в проекте юнит-тестом не накрыт: пропади из него эта строка — сборка
 * останется зелёной, а очередь ПК→телефон снова начнёт копить байты объектов человека молча.
 *
 * Спрашивается именно тело `main()`, а не файл целиком: по всему файлу сторож оставался зелёным
 * и тогда, когда вызов вынесли из запуска в функцию, которую никто не зовёт, — а человек,
 * запуская Point, всё равно не забывал бы ничего.
 *
 * Живёт в `:checks` (#1293): проверка читает `:desktop` текстом.
 */
class StartupForgetsAbandonedTest {

    @Test
    fun `запуск компьютера зовёт уборку брошенного`() {
        val file = code(File(repo, "desktop/src/main/kotlin/com/point/desktop/Main.kt").readText())
        val startup = bodyOf(file, "fun main(")

        assertTrue("у запуска нет тела", startup != null)
        assertTrue("запуск ничего не забывает", startup.orEmpty().contains("forgetAbandoned("))
    }
}

/**
 * Тело функции — от её `{` до парной `}`, или `null`, если такой функции в тексте нет.
 *
 * Скобки в тексте кода парны сами по себе: шаблон строки `${...}` закрывается там же, где
 * открылся, а фигурной скобкой-символом в этом файле никто не пишет.
 */
private fun bodyOf(code: String, signature: String): String? {
    val at = code.indexOf(signature)
    if (at < 0) return null

    val open = code.indexOf('{', at)
    if (open < 0) return null

    var depth = 0
    for (i in open until code.length) {
        when (code[i]) {
            '{' -> depth++
            '}' -> if (--depth == 0) return code.substring(open, i + 1)
        }
    }
    return null
}

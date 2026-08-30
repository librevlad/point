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
 * Живёт в `:checks` (#1293): проверка читает `:desktop` текстом.
 */
class StartupForgetsAbandonedTest {

    @Test
    fun `запуск компьютера зовёт уборку брошенного`() {
        val main = code(File(repo, "desktop/src/main/kotlin/com/point/desktop/Main.kt").readText())

        assertTrue("запуск ничего не забывает", main.contains("forgetAbandoned("))
    }
}

package com.point.checks

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тип записи знает общая таблица, а не каждый модуль по-своему (#867).
 *
 * Живой прогон владельца: голосовое сообщение, отданное Point, возвращалось файлом `.bin`.
 * Тип аудио не знала ни одна из трёх собственных табличек — на ПК при приёме, на телефоне
 * при сохранении и при передаче наружу, — хотя общая таблица (#840) его знала всё это время.
 *
 * Живёт в `:checks` (#1293): проверка читает файлы `:data` и `:desktop`. Сама таблица типов
 * проверяется тестами `:core:flow`, где она и объявлена.
 */
class OneTableOfAudioTypesTest {

    @Test
    fun `никто не заводит свою таблицу типов заново`() {
        val guilty = listOf(
            "desktop/src/main/kotlin/com/point/desktop/Inbox.kt",
            "data/src/main/kotlin/com/point/data/MediaStoreExporter.kt",

            // Лист «Поделиться» берёт имя общим правилом выхода (#1146), внутри которого
            // живёт та же общая таблица типов.
            "data/src/main/kotlin/com/point/data/AndroidSharer.kt",
        ).filterNot {
            val text = File(repo, it).readText()
            text.contains("extensionForMime(") || text.contains("outboundFileName(")
        }

        assertTrue("своя таблица типов: $guilty", guilty.isEmpty())
    }
}

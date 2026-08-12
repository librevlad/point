package com.point.core.flow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Голосовое не превращается в `bin` (#867).
 *
 * Живой прогон владельца: голосовое сообщение, отданное Point, возвращалось файлом `.bin`.
 * Причина не одна — тип аудио не знала ни одна из трёх собственных табличек: на ПК при
 * приёме, на телефоне при сохранении и при передаче наружу. Общая таблица (#840) его знала
 * всё это время.
 */
class VoiceKeepsItsNameTest {

    private val repo = File("../..")

    @Test
    fun `у записи есть расширение, а не bin`() {
        listOf(
            "audio/ogg" to "ogg",
            "audio/mpeg" to "mp3",
            "audio/mp4" to "m4a",
            "audio/wav" to "wav",
            "audio/flac" to "flac",
            "audio/aac" to "aac",
        ).forEach { (mime, ext) ->
            assertEquals(mime, ext, extensionForMime(mime))
        }
    }

    /**
     * `audio/ogg; codecs=opus` — это то, чем помечают голосовое мессенджеры. Параметр после
     * точки с запятой не должен превращать знакомый тип в незнакомый.
     */
    @Test
    fun `параметр в типе не делает знакомую запись незнакомой`() {
        assertEquals("ogg", extensionForMime("audio/ogg; codecs=opus"))
        assertEquals("mp3", extensionForMime("AUDIO/MPEG"))
    }

    /**
     * На компьютере тип объекта определяется по имени файла. Пока общая таблица не знала
     * `opus`, `amr` и `3gp`, голосовое приезжало туда «потоком байтов» — и этот неверный тип
     * ехал с ним дальше: в имя копии, в отправку на телефон, в запрос к сервису (#869).
     */
    @Test
    fun `запись узнаётся по имени файла, а не остаётся потоком байтов`() {
        listOf("голос.opus", "запись.oga", "диктофон.amr", "звук.3gp", "трек.wma", "старое.aif")
            .forEach { name ->
                assertTrue(name, mimeForName(name).startsWith("audio/"))
            }
    }

    /**
     * Обратный ход не должен сбиться: у `audio/ogg` расширение `ogg`, хотя тот же тип теперь
     * знают ещё `oga` и `opus`.
     */
    @Test
    fun `у типа остаётся своё привычное расширение`() {
        assertEquals("ogg", extensionForMime("audio/ogg"))
        assertEquals("aiff", extensionForMime("audio/aiff"))
    }

    @Test
    fun `набор форматов записи у классификатора и общей таблицы не расходится`() {
        val known = listOf(
            "ogg", "oga", "opus", "m4a", "mp3", "wav", "amr", "aac", "flac", "aiff", "aif", "3gp", "wma",
        )
        val unknown = known.filterNot { mimeForName("x.$it").startsWith("audio/") }

        assertTrue("общая таблица не знает: $unknown", unknown.isEmpty())
    }

    @Test
    fun `никто не заводит свою таблицу типов заново`() {
        val guilty = listOf(
            "desktop/src/main/kotlin/com/point/desktop/Inbox.kt",
            "data/src/main/kotlin/com/point/data/MediaStoreExporter.kt",
            "data/src/main/kotlin/com/point/data/AndroidSharer.kt",
        ).filterNot { File(repo, it).readText().contains("extensionForMime(") }

        assertTrue("своя таблица типов: $guilty", guilty.isEmpty())
    }
}

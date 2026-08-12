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

package com.point

import com.point.core.flow.FailedInvestigation
import com.point.core.flow.INVESTIGATION_FAILED
import com.point.core.flow.NO_TEXT_PAYLOAD
import com.point.core.flow.investigated
import com.point.core.flow.ownWords
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.ui.failedNote
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Заметка «Не удалось посмотреть» на экране объекта написана нашими словами (#1225).
 *
 * Дорога до человека настоящая и без единого перевода посередине: сорвавшееся исследование →
 * `FailedInvestigation.reason` → `failedNote()` → экран объекта. По ней доезжало «End-of-File,
 * expected line at offset 25» от разбора PDF: чужая техническая строка про смещение в байтах
 * стояла на русском экране рядом с нашими причинами.
 *
 * Проверяется здесь, а не у каждого исследования: правило одно на все тринадцать, и падать
 * оно должно там, где написано, а не в тринадцати местах по очереди.
 */
class OurWordsReachTheScreenTest {

    private fun noteFor(reason: String): String =
        failedNote(listOf(FailedInvestigation(CapabilityId("pdf-image-shape"), "PDF", reason))).orEmpty()

    @Test
    fun `чужой текст исключения до заметки не доезжает`() = runTest {
        val result = investigated {
            throw java.io.IOException("End-of-File, expected line at offset 25 at /data/user/0/com.point/x.pdf")
        }

        val note = noteFor((result as ActionResult.Failure).reason)

        assertFalse("латиница в лице продукта: $note", note.any { it in 'a'..'z' || it in 'A'..'Z' })
        assertFalse("смещение в байтах на экране: $note", note.any(Char::isDigit))
        assertTrue(note, note.contains(INVESTIGATION_FAILED))
    }

    @Test
    fun `своё слово слоя проходит как есть`() = runTest {
        val result = investigated { ownWords(NO_TEXT_PAYLOAD) }

        assertEquals(NO_TEXT_PAYLOAD, (result as ActionResult.Failure).reason)
        assertTrue(noteFor(result.reason).contains(NO_TEXT_PAYLOAD))
    }

    /** Своя причина исследования была и остаётся сильнее всего остального (#570). */
    @Test
    fun `объявленная причина исследования закрывает и своё, и чужое`() = runTest {
        val ours = "Архив не открылся — он повреждён или обрезан"

        val result = investigated(whenFailed = ours) { throw java.util.zip.ZipException("Central Directory") }

        assertEquals(ours, (result as ActionResult.Failure).reason)
    }
}

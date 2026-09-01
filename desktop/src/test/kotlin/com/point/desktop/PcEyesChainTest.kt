package com.point.desktop

import com.point.core.flow.FREE_LIMITS_SPENT_TEXT
import com.point.core.flow.Realizer
import com.point.core.flow.capabilities.OcrCapability
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чтение снимка на компьютере идёт очередью, как на телефоне (#1377).
 *
 * Слова владельца 01.09.2026: «дневная квота вранье, у нас куча сервисов». Компьютер брал
 * первого исполнителя и на его отказе останавливался — дневной предел ocr.space выходил
 * человеку как «На сегодня бесплатное чтение закончилось», хотя рядом жила связка ключей.
 */
class PcEyesChainTest {

    private val image = PointObject("i", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE))

    private class Spent : Realizer {
        override val capabilityId = OcrCapability.ID
        override val meta = com.point.core.flow.RealizerMeta(priority = 10)
        var asked = false
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            asked = true
            return ActionResult.Failure(FREE_LIMITS_SPENT_TEXT, recoverable = true)
        }
    }

    private class Eyes(private val text: String) : Realizer {
        override val capabilityId = OcrCapability.ID
        override val meta = com.point.core.flow.RealizerMeta(priority = 90)
        var asked = false
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            asked = true
            val file = java.io.File.createTempFile("point-", ".txt").apply { writeText(text); deleteOnExit() }
            return ActionResult.Success(
                ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(file.absolutePath), emptyMap()),
            )
        }
    }

    @Test
    fun `предел одного сервиса не кончает чтение — смотрят следующие`() = runTest {
        val spent = Spent()
        val eyes = Eyes("накладная 1187")
        val resolver = DesktopResolver(setOf(spent, eyes))

        val result = resolver.realizerFor(OcrCapability.ID, image.state).perform(image)

        assertTrue("первым спрошен сервис с пределом", spent.asked)
        assertTrue("за ним посмотрели глаза модели", eyes.asked)
        assertTrue("человек получил текст, а не «попробуйте завтра»", result is ActionResult.Success)
    }

    @Test
    fun `посмотрели все и никто не смог — человек читает последний отказ`() = runTest {
        val first = Spent()
        val second = Spent()
        val resolver = DesktopResolver(setOf(first, second))

        val result = resolver.realizerFor(OcrCapability.ID, image.state).perform(image)

        assertTrue(result is ActionResult.Failure)
        assertEquals(FREE_LIMITS_SPENT_TEXT, (result as ActionResult.Failure).reason)
    }

    @Test
    fun `единственный исполнитель очередью не оборачивается`() = runTest {
        val only = Eyes("текст")
        val resolver = DesktopResolver(setOf(only))

        assertEquals(only, resolver.realizerFor(OcrCapability.ID, image.state))
    }
}

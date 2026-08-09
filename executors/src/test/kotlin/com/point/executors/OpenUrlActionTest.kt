package com.point.executors

import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.UrlOpener
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Скрин владельца 2026-08-09: узел «Ссылка» из QR показывает «Нашёл ссылку», а
 * «Открыть ссылку» отвечает «Ссылка не найдена». Действие обязано видеть то же
 * знание, что и экран: entity.url → сам объект-ссылка → текст.
 */
class OpenUrlActionTest {

    private class SpyOpener : UrlOpener {
        var opened: String? = null
        override suspend fun open(url: String) { opened = url }
    }

    private fun extractor(vararg entities: Entity) = object : EntityExtractor {
        override suspend fun extract(text: String) = entities.toList()
    }

    @Test
    fun `узел ссылки открывается из знания, а не из файла`() = runTest {
        val opener = SpyOpener()
        val node = PointObject(
            "link", "text/plain", ScratchRef("/nowhere/ghost"),
            ObjectState(ObjectKind.URL),
            metadata = mapOf(META_ENTITY_PREFIX + "url" to "https://monobank.ua/r/HPzuka"),
        )

        val result = OpenUrlRealizer(extractor(), opener).perform(node, null)

        assertTrue("знание есть — действие обязано открыть: $result", result is ActionResult.Done)
        assertEquals("https://monobank.ua/r/HPzuka", opener.opened)
    }

    @Test
    fun `изображение с QR-ссылкой открывает её без распознавания текста`() = runTest {
        val opener = SpyOpener()
        val png = File.createTempFile("qrimg", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        val image = PointObject(
            "img", "image/png", ScratchRef(png.absolutePath),
            ObjectState(ObjectKind.IMAGE),
            metadata = mapOf(META_ENTITY_PREFIX + "url" to "https://check.monobank.ua/p/NaXzz"),
        )

        val result = OpenUrlRealizer(extractor(), opener).perform(image, null)

        assertTrue(result is ActionResult.Done)
        assertEquals("https://check.monobank.ua/p/NaXzz", opener.opened)
    }

    @Test
    fun `текст со ссылкой открывается через извлечение, как раньше`() = runTest {
        val opener = SpyOpener()
        val txt = File.createTempFile("txt", ".txt").apply { writeText("см. https://example.org/page"); deleteOnExit() }
        val obj = PointObject("t", "text/plain", ScratchRef(txt.absolutePath), ObjectState(ObjectKind.TEXT))

        val result = OpenUrlRealizer(
            extractor(Entity(EntityType.URL, "https://example.org/page")),
            opener,
        ).perform(obj, null)

        assertTrue(result is ActionResult.Done)
        assertEquals("https://example.org/page", opener.opened)
    }

    @Test
    fun `ссылки нет нигде — честная неудача операции`() = runTest {
        val opener = SpyOpener()
        val txt = File.createTempFile("txt", ".txt").apply { writeText("без ссылок"); deleteOnExit() }
        val obj = PointObject("t", "text/plain", ScratchRef(txt.absolutePath), ObjectState(ObjectKind.TEXT))

        val result = OpenUrlRealizer(extractor(), opener).perform(obj, null)

        assertTrue(result is ActionResult.Failure)
        assertEquals(null, opener.opened)
    }
}

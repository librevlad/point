package com.point.desktop

import com.point.core.flow.META_ENTITY_URL
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadActionTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeDownloader : VideoDownloader {
        var started: String? = null
        override fun available() = true
        override fun start(url: String): Boolean { started = url; return true }
    }

    /**
     * Объект-ссылка такой, каким его рождает дверь приёма: адрес знанием, байты рядом.
     * Своего разбора у действия больше нет — правило одно на всё приложение (#999).
     */
    private fun link(content: String, known: String? = null): PointObject {
        val f = File(tmp.root, "u.uri").apply { writeText(content) }
        return PointObject(
            id = "id",
            mime = "text/uri-list",
            uri = ScratchRef(f.absolutePath),
            state = ObjectState(ObjectKind.URL),
            metadata = known?.let { mapOf(META_ENTITY_URL to it) }.orEmpty(),
        )
    }

    @Test
    fun `качается адрес, который объект знает про себя`() = runBlocking {
        val address = "https://youtu.be/abc123"
        val downloader = FakeDownloader()

        val result = PcDownloadRealizer(downloader).perform(link("не отсюда\n", known = address), null)

        assertTrue(result is ActionResult.Done)
        assertEquals(address, downloader.started)
    }

    @Test
    fun `знания нет — адрес читается из файла с комментариями и переводами CRLF`() = runBlocking {
        val address = "https://youtu.be/abc123"
        val downloader = FakeDownloader()

        val result = PcDownloadRealizer(downloader)
            .perform(link("# сохранено из браузера\r\n\r\n$address\r\nhttps://youtu.be/vtoroy\r\n"), null)

        assertTrue(result is ActionResult.Done)
        assertEquals(address, downloader.started)
    }

    @Test
    fun `no link in the object is a recoverable failure`() = runBlocking {
        val result = PcDownloadRealizer(FakeDownloader()).perform(link("просто текст"), null)
        assertTrue((result as ActionResult.Failure).recoverable)
    }
}

package com.point.desktop

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

/** «Скачать видео» (#80 v2): the received object carries a URL; yt-dlp runs behind a seam. */
class DownloadActionTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeDownloader : VideoDownloader {
        var started: String? = null
        override fun available() = true
        override fun start(url: String): Boolean { started = url; return true }
    }

    private fun obj(content: String): PointObject {
        val f = File(tmp.root, "u.txt").apply { writeText(content) }
        return PointObject("id", "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    @Test
    fun `starts the download for the first http link in the object`() = runBlocking {
        val downloader = FakeDownloader()
        val result = PcDownloadRealizer(downloader).perform(obj("смотри!\nhttps://youtu.be/abc123\nи ещё"), null)

        assertTrue(result is ActionResult.Done)
        assertEquals("https://youtu.be/abc123", downloader.started)
    }

    @Test
    fun `no link in the object is a recoverable failure`() = runBlocking {
        val result = PcDownloadRealizer(FakeDownloader()).perform(obj("просто текст"), null)
        assertTrue((result as ActionResult.Failure).recoverable)
    }
}

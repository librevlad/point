package com.point.executors

import com.point.core.flow.Clipboard
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CopyActionTest {

    private class FakeClipboard : Clipboard {
        var copied: String? = null
        override suspend fun copy(text: String, label: String) { copied = text }
    }

    private fun textObj(content: String): PointObject {
        val f = File.createTempFile("copy", ".txt").apply { writeText(content); deleteOnExit() }
        return PointObject("id", "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    @Test
    fun `copies the trimmed text content and reports done`() = runTest {
        val clip = FakeClipboard()
        val result = CopyRealizer(clip).perform(textObj("  привет  "), null)
        assertTrue(result is ActionResult.Done)
        assertEquals("привет", clip.copied)
    }

    @Test
    fun `blank text is a recoverable failure`() = runTest {
        val result = CopyRealizer(FakeClipboard()).perform(textObj("   "), null)
        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `copy-card puts only the card digits on the clipboard`() = runTest {
        val clip = FakeClipboard()
        val extractor = object : EntityExtractor {
            override suspend fun extract(text: String) =
                listOf(Entity(EntityType.PAYMENT_CARD, "4111 1111 1111 1111"))
        }
        val result = CopyCardRealizer(extractor, clip).perform(textObj("карта 4111 1111 1111 1111"), null)
        assertTrue(result is ActionResult.Done)
        assertEquals("4111111111111111", clip.copied)
    }
}

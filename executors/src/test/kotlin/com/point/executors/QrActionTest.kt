package com.point.executors

import com.point.core.flow.QrEncoder
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QrActionTest {

    private class FakeQrEncoder : QrEncoder {
        var encoded: String? = null
        override suspend fun encode(text: String): ScratchRef {
            encoded = text
            return ScratchRef("/tmp/qr.png")
        }
    }

    private fun textObject(content: String): PointObject {
        val file = File.createTempFile("point-", ".txt").apply { writeText(content); deleteOnExit() }
        return PointObject("id", "text/plain", ScratchRef(file.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    @Test
    fun `encodes text into a PNG image object`() = runTest {
        val qr = FakeQrEncoder()
        val result = QrRealizer(qr).perform(textObject("https://example.com"), null)
        assertTrue(result is ActionResult.Success)
        val obj = (result as ActionResult.Success).result
        assertEquals(ObjectKind.IMAGE, obj.type)
        assertEquals("image/png", obj.mime)
        assertEquals("https://example.com", qr.encoded)
    }

    @Test
    fun `blank text fails without calling the encoder`() = runTest {
        val qr = FakeQrEncoder()
        val result = QrRealizer(qr).perform(textObject("   "), null)
        assertTrue(result is ActionResult.Failure)
        assertNull(qr.encoded)
    }

    @Test
    fun `over-long text fails without calling the encoder`() = runTest {
        val qr = FakeQrEncoder()
        val result = QrRealizer(qr).perform(textObject("x".repeat(1001)), null)
        assertTrue(result is ActionResult.Failure)
        assertNull(qr.encoded)
    }
}

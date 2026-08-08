package com.point.executors

import com.point.core.flow.CollectionContent
import com.point.core.flow.ObjectStore
import com.point.core.flow.QrReader
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadQrActionTest {

    private fun imageObj() = PointObject(
        "id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_QR)),
    )

    private class TempStore : ObjectStore {
        var written: String? = null
        override suspend fun ingest(sourceUri: String, mime: String): PointObject = error("unused")
        override suspend fun ingestMultiple(sources: List<String>): PointObject = error("unused")
        override suspend fun put(result: ResultObject): PointObject = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = CollectionContent.empty<PointObject>()
        override suspend fun readText(obj: PointObject, limit: Int): String = ""
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("qrtest-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    @Test
    fun `decodes a QR into a TEXT object`() = runTest {
        val reader = object : QrReader { override suspend fun decode(imagePath: String) = "https://x.com" }
        val store = TempStore()
        val result = ReadQrRealizer(store, reader).perform(imageObj(), null)
        assertTrue(result is ActionResult.Success)
        val obj = (result as ActionResult.Success).result
        assertEquals(ObjectKind.TEXT, obj.type)
        assertEquals("https://x.com", File(obj.uri.value).readText())
    }

    @Test
    fun `no QR is a recoverable failure`() = runTest {
        val reader = object : QrReader { override suspend fun decode(imagePath: String): String? = null }
        val result = ReadQrRealizer(TempStore(), reader).perform(imageObj(), null)
        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `capability is gated on HAS_QR`() {
        val cap = ReadQrCapability()
        assertTrue(cap.accepts(ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_QR))))
        assertFalse(cap.accepts(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `an unreadable image is an honest read failure, not a missing QR`() = runTest {
        val blind = object : QrReader {
            override suspend fun decode(imagePath: String): String? = error("изображение не открылось")
        }

        val result = ReadQrRealizer(TempStore(), blind).perform(imageObj(), null)

        assertTrue(result is ActionResult.Failure)
        assertEquals("изображение не открылось", (result as ActionResult.Failure).reason)
        assertFalse("нельзя врать про отсутствие кода", result.reason.contains("не распознан"))
    }
}

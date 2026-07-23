package com.point.executors

import com.point.core.flow.ObjectStore
import com.point.core.flow.TextRecognizer
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
import java.io.File

/**
 * On-device OCR realizer — the chain's preferred (local, priority 10) head. A blank
 * recognition or an engine failure becomes a **recoverable** Failure so the Resolver
 * chain hands off to the cloud realizer.
 */
class DeviceOcrRealizerTest {

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun put(result: ResultObject) = error("unused")
        override suspend fun children(collection: PointObject) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    private fun recognizer(result: String) = object : TextRecognizer {
        override suspend fun recognize(obj: PointObject) = result
    }

    private fun throwingRecognizer() = object : TextRecognizer {
        override suspend fun recognize(obj: PointObject): String = error("engine init failed")
    }

    private val image = PointObject("id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE))

    @Test
    fun `recognised text becomes an on-device TEXT object`() = runTest {
        val result = DeviceOcrRealizer(store, recognizer("Привет из Tesseract")).perform(image)

        assertTrue(result is ActionResult.Success)
        val out = (result as ActionResult.Success).result
        assertEquals(ObjectKind.TEXT, out.type)
        assertEquals("on-device", out.metadata["engine"])
        assertEquals("Привет из Tesseract", File(out.uri.value).readText())
    }

    @Test
    fun `a blank recognition defers with a recoverable failure`() = runTest {
        val result = DeviceOcrRealizer(store, recognizer("   ")).perform(image)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }

    @Test
    fun `an engine failure defers with a recoverable failure`() = runTest {
        val result = DeviceOcrRealizer(store, throwingRecognizer()).perform(image)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }
}

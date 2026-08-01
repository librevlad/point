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
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
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

    @Test
    fun `reuses the OCR sidecar of an enriched image instead of re-running the engine`() = runTest {
        val side = File.createTempFile("ocr", ".txt").apply { writeText("Уже распознано"); deleteOnExit() }
        val enriched = image.copy(metadata = mapOf(com.point.core.flow.META_OCR_TEXT_REF to side.absolutePath))

        val result = DeviceOcrRealizer(store, throwingRecognizer()).perform(enriched)

        assertTrue(result is ActionResult.Success)
        val out = (result as ActionResult.Success).result
        assertEquals(ObjectKind.TEXT, out.type)
        assertEquals("Уже распознано", File(out.uri.value).readText())
    }

    // --- #288: стадия ровно там, где работа ---

    @Test
    fun `запуск движка на устройстве называет себя`() = runTest {
        val heard = stagesHeard { DeviceOcrRealizer(store, recognizer("Привет из Tesseract")).perform(image) }

        assertEquals(listOf("Читаю текст на устройстве"), heard)
    }

    @Test
    fun `готовый сайдкар не рождает стадии — движок не запускался, врать не о чем`() = runTest {
        val side = File.createTempFile("ocr", ".txt").apply { writeText("Уже распознано"); deleteOnExit() }
        val enriched = image.copy(metadata = mapOf(com.point.core.flow.META_OCR_TEXT_REF to side.absolutePath))

        val heard = stagesHeard { DeviceOcrRealizer(store, throwingRecognizer()).perform(enriched) }

        assertTrue("работы не было — и слов о ней нет", heard.isEmpty())
    }
}

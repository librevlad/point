package com.point.executors

import com.point.core.flow.ExternalEye
import com.point.core.flow.ExternalReading
import com.point.core.flow.InvestigationState
import com.point.core.flow.KnownCapabilities
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.ObjectStore
import com.point.core.flow.investigationStateOf
import com.point.core.model.ActionResult
import com.point.core.model.Feature
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
 * Сильное чтение — знание того же объекта, а не новый объект (#1097, #1009).
 *
 * «Прочитать сильнее» рождало дочерний TEXT: исправленная ошибка жила у ребёнка, исходник
 * продолжал носить неверное значение, и ни один экран не говорил, что есть второе прочтение.
 */
class StrongerReadingEnrichesSourceTest {

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(
            result: ResultObject,
            from: PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    private val image = PointObject("id", "image/jpeg", ScratchRef("/tmp/chek.jpg"), ObjectState(ObjectKind.IMAGE))

    private fun eye(text: String) = object : ExternalEye {
        override fun available() = true
        override suspend fun read(obj: PointObject) = ExternalReading(text, "mistral", "eu")
    }

    @Test fun `сильное чтение ложится знанием на исходник, а не рождает ребёнка`() = runTest {
        val page = "Tel: 918-682-1561"
        val result = ExternalEyeCloudOcrRealizer(eye(page), store).perform(image)

        assertTrue("родился объект вместо знания", result is ActionResult.Done)
        val found = (result as ActionResult.Done).findings!!
        assertTrue("нет ни одного нового узла", found.objects.isEmpty())
        assertTrue(Feature.HAS_TEXT in found.features)
        assertEquals(page, File(found.metadata.getValue(META_OCR_TEXT_REF)).readText())
        assertEquals(
            "вопрос чтения закрыт находкой",
            InvestigationState.FOUND,
            investigationStateOf(found.metadata, KnownCapabilities.IMAGE_TEXT),
        )
    }

    @Test fun `бессмыслица от модели остаётся отказом, а не знанием`() = runTest {
        val result = ExternalEyeCloudOcrRealizer(eye("////////////"), store).perform(image)

        assertTrue(result is ActionResult.Failure)
    }
}

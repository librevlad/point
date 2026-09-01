package com.point.core.flow

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

    private fun eye(text: String, reader: String = "mistral") = object : ExternalEye {
        override fun available() = true
        override suspend fun read(obj: PointObject) = ExternalReading(text, reader, "eu")
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

    /**
     * Отказ не называет исполнителя (#1259): на экране стояло «Не смог прочитать этот
     * снимок: ovh-qwen-vl отдала бессмыслицу». Ни `ovh-qwen-vl`, ни `ocr-space` человек не
     * заводил — идентификатор остаётся в metadata `engine` и в журнале.
     */
    @Test fun `отказ по нечитаемому ответу не называет исполнителя`() = runTest {
        val result = ExternalEyeCloudOcrRealizer(eye("////////////", reader = "ovh-qwen-vl"), store).perform(image)

        val said = (result as ActionResult.Failure).reason
        assertTrue(said, said.contains("переснять"))
        assertEquals("латиница в лице продукта: $said", "", said.filter { it in 'a'..'z' || it in 'A'..'Z' })
    }

    @Test fun `отписка сервиса «текста нет» — знание «не нашлось», а не текст исходника`() = runTest {
        // «Прочитать сильнее» на фото без надписей (#1054): «*[No text detected]*» ложилось
        // текстом объекта, и Point предлагал «Понять» и «Перевести» чужую отписку.
        val result = ExternalEyeCloudOcrRealizer(eye("*[No text detected]*"), store).perform(image)

        assertTrue("отписка стала срывом или объектом", result is ActionResult.Done)
        val found = (result as ActionResult.Done).findings!!
        assertTrue("нет ни одного нового узла", found.objects.isEmpty())
        assertTrue("отписка легла текстом исходника", META_OCR_TEXT_REF !in found.metadata)
        assertTrue("объекту приписан текст, которого нет", Feature.HAS_TEXT !in found.features)
        assertEquals(
            "вопрос чтения закрыт честным «не нашлось»",
            InvestigationState.NOT_FOUND,
            investigationStateOf(found.metadata, KnownCapabilities.IMAGE_TEXT),
        )
    }
}

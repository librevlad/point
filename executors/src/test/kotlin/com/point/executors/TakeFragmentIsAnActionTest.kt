package com.point.executors

import com.point.core.flow.Box
import com.point.core.flow.CropEvidence
import com.point.core.flow.EvidenceCropper
import com.point.core.flow.EvidenceImage
import com.point.core.flow.Focus
import com.point.core.flow.GraphState
import com.point.core.flow.META_SELECTION_REGION
import com.point.core.flow.META_SELECTION_SOURCE
import com.point.core.flow.ObjectStore
import com.point.core.flow.withFocus
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Взять фрагмент» — действие над областью, а не кнопка внутри инструмента (#742).
 *
 * Экран выделения заменён на Focus, и вместе с ним ушла кнопка, создававшая из обведённой
 * области самостоятельный объект. Возвращать её внутрь Focus нельзя: там «✓ — единственное
 * завершение», а два завершения снова заставили бы человека выбирать устройство результата
 * вместо намерения.
 */
class TakeFragmentIsAnActionTest {

    private val capability = TakeFragmentCapability()

    private val shot = PointObject(
        id = "shot",
        mime = "image/jpeg",
        uri = ScratchRef(File.createTempFile("shot", ".jpg").apply { deleteOnExit() }.absolutePath),
        state = ObjectState(ObjectKind.IMAGE),
    )

    private val area = Focus(shot.id, region = Box(10f, 20f, 110f, 90f))

    /** Так объект видит исполнитель: тот же объект, но с показанной областью (ADR-0001 §10). */
    private val focused = shot.copy(metadata = withFocus(shot.metadata, area))

    private class Scissors(val image: EvidenceImage?) : EvidenceCropper {
        var asked: CropEvidence? = null
        override suspend fun crop(evidence: CropEvidence): EvidenceImage? {
            asked = evidence
            return image
        }
    }

    private val scratch = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(
            result: ResultObject,
            from: com.point.core.model.PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("fragment-", ".$extension").apply { deleteOnExit() }.absolutePath)

        override suspend fun clear() = Unit
    }

    private fun realizer(cropper: EvidenceCropper, turnedInFile: Int = 0) =
        TakeFragmentRealizer(cropper, scratch) { turnedInFile }

    @Test
    fun `у объекта с показанной областью действие есть`() {
        assertTrue(capability.accepts(GraphState(focused, focus = area)))
    }

    @Test
    fun `без Focus действия нет`() {
        assertFalse("брать нечего, пока область не показана", capability.accepts(GraphState(shot)))
    }

    @Test
    fun `Focus без области действия не даёт`() {
        val words = Focus(shot.id, atomIds = listOf("w1", "w2"))

        assertFalse(capability.accepts(GraphState(shot, focus = words)))
    }

    @Test
    fun `берётся именно показанная область`() = runTest {
        val scissors = Scissors(EvidenceImage(byteArrayOf(1, 2, 3), widthPx = 100, heightPx = 70))

        realizer(scissors).perform(focused)

        assertEquals(Box(10f, 20f, 110f, 90f), scissors.asked?.region)
        assertEquals(shot.uri.value, scissors.asked?.imagePath)
    }

    /**
     * Фрагмент встаёт так же, как кадр, который человек видел, когда его выделял (#1389).
     *
     * Область режется из файла как есть, а камера могла записать файл повёрнутым. Пока это число
     * не называли, человек выделял ровный текст и получал картинку на боку — при том что и экран
     * объекта, и экран выделения, и превью области показывали кадр ровно.
     */
    @Test
    fun `фрагмент выходит развёрнутым так же, как виденный кадр`() = runTest {
        val scissors = Scissors(EvidenceImage(byteArrayOf(1, 2, 3), 100, 70))

        realizer(scissors, turnedInFile = 270).perform(focused)

        assertEquals(270, scissors.asked?.uprightDegrees)
    }

    @Test
    fun `рождается самостоятельный объект, а не знание о прежнем`() = runTest {
        val outcome = realizer(Scissors(EvidenceImage(byteArrayOf(1, 2, 3), 100, 70)))
            .perform(focused)

        val success = outcome as ActionResult.Success
        assertEquals(ObjectKind.IMAGE, success.result.type)
        assertTrue("файл фрагмента не записан", File(success.result.uri.value).length() > 0)
    }

    @Test
    fun `знание области остаётся при новом объекте`() = runTest {
        val outcome = realizer(Scissors(EvidenceImage(byteArrayOf(1, 2, 3), 100, 70)))
            .perform(focused) as ActionResult.Success

        assertEquals(shot.id, outcome.result.metadata[META_SELECTION_SOURCE])
        assertEquals("10.0 20.0 110.0 90.0", outcome.result.metadata[META_SELECTION_REGION])
    }

    @Test
    fun `вырезать не вышло — сказано, и это поправимо`() = runTest {
        val outcome = realizer(Scissors(image = null)).perform(focused)

        val failure = outcome as ActionResult.Failure
        assertTrue("отказ обязан быть поправимым: область можно показать заново", failure.recoverable)
    }
}

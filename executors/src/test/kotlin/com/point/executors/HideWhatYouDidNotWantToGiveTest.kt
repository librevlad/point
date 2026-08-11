package com.point.executors

import com.point.core.flow.Box
import com.point.core.flow.EvidenceImage
import com.point.core.flow.Focus
import com.point.core.flow.GraphState
import com.point.core.flow.ImageRedactor
import com.point.core.flow.META_SELECTION_SOURCE
import com.point.core.flow.ObjectStore
import com.point.core.flow.partsOfWire
import com.point.core.flow.withFocus
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Замазать на снимке то, что не хотел отдавать (#549).
 *
 * Человек отправляет фото из общественного места, с документом на столе, с чужими детьми в
 * кадре — и сегодня либо отправляет как есть, либо идёт в другой редактор.
 *
 * Мажет человек, а не детектор (решение владельца 05.08.2026): автоматический поиск лиц
 * обещал бы безопасность, которой не даёт — он не увидит ни номер машины в окне, ни чужую
 * переписку на экране, ни бейдж на груди.
 */
class HideWhatYouDidNotWantToGiveTest {

    private val capability = HideAreaCapability()

    private val shot = PointObject(
        id = "shot",
        mime = "image/jpeg",
        uri = ScratchRef(File.createTempFile("shot", ".jpg").apply { deleteOnExit() }.absolutePath),
        state = ObjectState(ObjectKind.IMAGE),
    )

    private val faces = listOf(Box(10f, 20f, 60f, 70f), Box(120f, 30f, 160f, 80f))

    private val shown = Focus(shot.id, region = Box(10f, 20f, 160f, 80f), parts = faces)

    private val focused = shot.copy(metadata = withFocus(shot.metadata, shown))

    private class Marker(val image: EvidenceImage?) : ImageRedactor {
        var asked: List<Box>? = null
        override suspend fun hide(imagePath: String, places: List<Box>): EvidenceImage? {
            asked = places
            return image
        }
    }

    private val scratch = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(result: ResultObject) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("hidden-", ".$extension").apply { deleteOnExit() }.absolutePath)

        override suspend fun clear() = Unit
    }

    private fun realizer(redactor: ImageRedactor) = HideAreaRealizer(redactor, scratch)

    private fun ready() = Marker(EvidenceImage(byteArrayOf(9, 9, 9), 200, 100, extension = "png"))

    @Test
    fun `у снимка с показанными местами действие есть`() {
        assertTrue(capability.accepts(GraphState(focused, focus = shown)))
    }

    @Test
    fun `пока ничего не обведено, замазывать нечего`() {
        assertFalse(capability.accepts(GraphState(shot)))
    }

    /** Приёмка 3: несколько областей за один заход, а не по одной. */
    @Test
    fun `замазываются все обведённые места сразу`() = runTest {
        val marker = ready()

        realizer(marker).perform(focused)

        assertEquals(faces, marker.asked)
    }

    /** Приёмка 2: исходный объект остаётся как был. */
    @Test
    fun `рождается новый объект, а исходный не переписан`() = runTest {
        val was = File(shot.uri.value).readBytes()

        val outcome = realizer(ready()).perform(focused) as ActionResult.Success

        assertEquals(ObjectKind.IMAGE, outcome.result.type)
        assertTrue("исходный снимок переписан", File(shot.uri.value).readBytes().contentEquals(was))
        assertTrue("новый файл не записан", File(outcome.result.uri.value).length() > 0)
        assertTrue(outcome.result.uri.value != shot.uri.value)
    }

    @Test
    fun `новый объект помнит, откуда он и что на нём закрыто`() = runTest {
        val outcome = realizer(ready()).perform(focused) as ActionResult.Success

        assertEquals(shot.id, outcome.result.metadata[META_SELECTION_SOURCE])
        assertEquals(faces, partsOfWire(outcome.result.metadata[META_HIDDEN_PLACES]))
    }

    /** Одно обведённое место — тоже место: части заводить необязательно. */
    @Test
    fun `одна обведённая область работает так же`() = runTest {
        val one = Focus(shot.id, region = Box(5f, 5f, 25f, 25f))
        val marker = ready()

        realizer(marker).perform(shot.copy(metadata = withFocus(shot.metadata, one)))

        assertEquals(listOf(Box(5f, 5f, 25f, 25f)), marker.asked)
    }

    @Test
    fun `замазать не вышло — сказано, и это поправимо`() = runTest {
        val outcome = realizer(Marker(image = null)).perform(focused)

        val failure = outcome as ActionResult.Failure
        assertTrue("отказ обязан быть поправимым: места можно показать заново", failure.recoverable)
    }

    @Test
    fun `без показанных мест исполнитель не выдумывает, что закрыть`() = runTest {
        val marker = ready()

        val outcome = realizer(marker).perform(shot)

        assertTrue(outcome is ActionResult.Failure)
        assertTrue("замазал что-то, чего человек не показывал", marker.asked == null)
    }
}

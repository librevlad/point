package com.point.data

import com.point.core.flow.META_UNUSABLE_REASON
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ADR-0001 §9: «архив не прочитался» и «архив не из картинок» — разные ответы.
 * Битый ZIP не имеет права ни на NOT_FOUND, ни тем более на FOUND от частичного счёта.
 *
 * #570: и он же не имеет права заговорить с человеком чужими словами. Обломок вместо
 * архива — знание о самом объекте, такое же, как пустой файл.
 */
class ZipImagesInvestigationTest {

    private val enricher = ZipImagesInvestigationRealizer()

    private val brokenArchive = "Архив не открылся — он повреждён или обрезан"

    private fun zipObject(bytes: ByteArray): PointObject {
        val file = File.createTempFile("zip-test", ".zip").apply {
            writeBytes(bytes)
            deleteOnExit()
        }
        return PointObject("z", "application/zip", ScratchRef(file.absolutePath), ObjectState(ObjectKind.ZIP))
    }

    private fun zipOf(vararg names: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            names.forEach { name ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(ByteArray(512) { 7 })
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun brokenTold(result: ActionResult) {
        val findings = (result as? ActionResult.Done)?.findings
        assertNotNull("битый архив обязан оставить знание, а не пустоту-" + result, findings)
        assertTrue("годность объекта названа", Feature.UNUSABLE in findings!!.features)
        assertFalse("частичный счёт не находка", Feature.ZIP_OF_IMAGES in findings.features)
        assertEquals(brokenArchive, findings.metadata[META_UNUSABLE_REASON])
    }

    @Test
    fun `an all-images zip is found`() = runTest {
        val delta = enricher.look(zipObject(zipOf("a.png", "b.jpg")))

        assertTrue(Feature.ZIP_OF_IMAGES in delta.features)
    }

    @Test
    fun `a mixed zip is an honest no-match, as before`() = runTest {
        val delta = enricher.look(zipObject(zipOf("a.png", "readme.txt")))

        assertTrue(delta.features.isEmpty())
    }

    @Test
    fun `мусор вместо архива — названная негодность, а не пустой ответ`() = runTest {
        brokenTold(enricher.perform(zipObject(ByteArray(64) { 42 }), null))
    }

    @Test
    fun `обрезанный после картинок архив говорит про обрыв, а не про находку`() = runTest {

        val whole = zipOf("a.png", "b.png")
        val cut = whole.copyOfRange(0, whole.size / 2)

        brokenTold(enricher.perform(zipObject(cut), null))
    }

    @Test
    fun `слова платформы про центральный каталог человеку не показываются`() = runTest {
        val result = enricher.perform(zipObject(ByteArray(64) { 42 }), null)

        val said = (result as ActionResult.Done).findings!!.metadata[META_UNUSABLE_REASON].orEmpty()
        assertFalse("латиницы в лице продукта нет", said.any { it in 'a'..'z' || it in 'A'..'Z' })
    }

    @Test
    fun `пропавший файл — неудача попытки, и тоже своими словами`() = runTest {
        val ghost = PointObject("z", "application/zip", ScratchRef("/nowhere/gone.zip"), ObjectState(ObjectKind.ZIP))

        val result = enricher.perform(ghost, null)

        assertTrue(result is ActionResult.Failure)
        val reason = (result as ActionResult.Failure).reason
        assertFalse("путь из недр наружу не выходит", reason.contains("nowhere"))
        assertFalse("латиницы в лице продукта нет", reason.any { it in 'a'..'z' || it in 'A'..'Z' })
    }
}

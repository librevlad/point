package com.point.data

import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ADR-0001 §9: «архив не прочитался» и «архив не из картинок» — разные ответы.
 * Битый ZIP не имеет права ни на NOT_FOUND, ни тем более на FOUND от частичного счёта.
 */
class ZipImagesInvestigationTest {

    private val enricher = ZipImagesInvestigationRealizer()

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
    fun `garbage bytes are a failure, not an empty answer`() = runTest {
        val result = enricher.perform(zipObject(ByteArray(64) { 42 }), null)

        assertTrue("мусор вместо архива обязан быть неудачей-" + result, result is ActionResult.Failure)
    }

    @Test
    fun `a zip truncated after images never claims found`() = runTest {

        val whole = zipOf("a.png", "b.png")
        val cut = whole.copyOfRange(0, whole.size / 2)

        val result = enricher.perform(zipObject(cut), null)

        assertTrue(
            "обрыв после картинок — не основание для знания-" + result,
            result is ActionResult.Failure,
        )
    }

    @Test
    fun `a missing file is a failure, not an empty answer`() = runTest {
        val ghost = PointObject("z", "application/zip", ScratchRef("/nowhere/gone.zip"), ObjectState(ObjectKind.ZIP))

        val result = enricher.perform(ghost, null)

        assertTrue(result is ActionResult.Failure)
    }
}

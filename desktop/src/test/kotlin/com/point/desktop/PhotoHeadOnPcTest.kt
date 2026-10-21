package com.point.desktop

import com.point.core.flow.META_ENTITY_GEO
import com.point.core.flow.photoHead
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** Снимок на компьютере рассказывает о себе и очищается, как на телефоне (#547, #1379). */
class PhotoHeadOnPcTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `компьютер сам узнаёт место съёмки`() = runBlocking {
        val photo = photo(withHead = true)

        val result = PcPhotoHeadRealizer().perform(photo, null) as ActionResult.Done

        assertTrue(Feature.HAS_GEO in result.findings!!.features)
        assertEquals("50.45000, 30.52000", result.findings!!.metadata[META_ENTITY_GEO])
    }

    @Test fun `снимок без записей — ответ «не найдено», а не сбой`() = runBlocking {
        val result = PcPhotoHeadRealizer().perform(photo(withHead = false), null) as ActionResult.Done

        assertTrue(result.findings!!.features.isEmpty())
    }

    @Test fun `очищенный снимок без записей и стоит, как его сняли`() = runBlocking {
        val photo = photo(withHead = true)
        val state = photo.state.with(Feature.HAS_GEO)
        assertTrue(com.point.core.flow.CleanMetadataCapability().accepts(state))

        val result = PcCleanMetadataRealizer(PcScratchStore(tmp.newFolder())).perform(photo, null)

        val made = (result as ActionResult.Success).result
        val bytes = java.io.File(made.uri.value).readBytes()
        assertNull("служебные записи переехали в очищенный снимок", photoHead(bytes))
        val image = ImageIO.read(java.io.File(made.uri.value))

        // Метка поворота ушла вместе с записями: кадр 40×20 «на боку» обязан стать 20×40.
        assertEquals(20, image.width)
        assertEquals(40, image.height)
        assertEquals(ObjectKind.IMAGE, made.type)
    }

    private fun photo(withHead: Boolean): PointObject {
        val image = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
        val plain = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        val bytes = if (withHead) spliced(plain, turnedPlacedExif()) else plain
        val file = tmp.newFile("снимок.jpg").apply { writeBytes(bytes) }
        return PointObject(
            "photo",
            "image/jpeg",
            ScratchRef(file.absolutePath),
            ObjectState(ObjectKind.IMAGE),
            metadata = mapOf("name" to "снимок.jpg"),
        )
    }
}

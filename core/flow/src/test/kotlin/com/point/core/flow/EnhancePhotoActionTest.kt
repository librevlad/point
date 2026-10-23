package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Улучшить фото» (#433): превращение в новый снимок, исходник не трогается. */
class EnhancePhotoActionTest {

    private val photo = PointObject("id", "image/jpeg", ScratchRef("/tmp/in.jpg"), ObjectState(ObjectKind.IMAGE))

    @Test
    fun `улучшенный снимок — новый объект, а не правка исходника`() = runTest {
        var asked: String? = null
        val enhancer = object : PhotoEnhancer {
            override suspend fun enhance(imagePath: String): ScratchRef {
                asked = imagePath
                return ScratchRef("/tmp/out.jpg")
            }
        }

        val r = EnhancePhotoRealizer(enhancer).perform(photo, null)

        val out = (r as ActionResult.Success).result
        assertEquals("/tmp/in.jpg", asked)
        assertEquals("/tmp/out.jpg", out.uri.value)
        assertEquals(ObjectKind.IMAGE, out.type)
        assertEquals("enhance-photo", out.metadata["op"])
    }

    @Test
    fun `сорвавшееся улучшение — отказ с которого можно начать заново`() = runTest {
        val enhancer = object : PhotoEnhancer {
            override suspend fun enhance(imagePath: String): ScratchRef = error("broken")
        }

        val r = EnhancePhotoRealizer(enhancer).perform(photo, null)

        assertTrue(r is ActionResult.Failure && r.recoverable)
    }

    @Test
    fun `без пакета обработки снимков исполнитель недоступен`() {
        val enhancer = object : PhotoEnhancer {
            override suspend fun enhance(imagePath: String): ScratchRef = error("unused")
        }
        assertFalse(EnhancePhotoRealizer(enhancer) { false }.isAvailable())
    }

    @Test
    fun `действие предлагается снимку и только ему`() {
        val c = EnhancePhotoCapability()
        assertTrue(c.accepts(ObjectState(ObjectKind.IMAGE)))
        assertFalse(c.accepts(ObjectState(ObjectKind.TEXT)))
        assertFalse(c.accepts(ObjectState(ObjectKind.PDF)))
    }
}

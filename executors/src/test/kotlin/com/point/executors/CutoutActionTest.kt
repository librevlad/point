package com.point.executors

import com.point.core.flow.BackgroundRemover
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

class CutoutActionTest {

    private fun imageObj() =
        PointObject("id", "image/jpeg", ScratchRef("/tmp/photo.jpg"), ObjectState(ObjectKind.IMAGE))

    @Test
    fun `success yields a transparent PNG image object`() = runTest {
        val remover = object : BackgroundRemover {
            override suspend fun cutout(imagePath: String) = ScratchRef("/tmp/cut.png")
        }
        val result = CutoutRealizer(remover).perform(imageObj(), null)
        assertTrue(result is ActionResult.Success)
        val obj = (result as ActionResult.Success).result
        assertEquals(ObjectKind.IMAGE, obj.type)
        assertEquals("image/png", obj.mime)
    }

    @Test
    fun `no subject is a recoverable failure with the reason`() = runTest {
        val remover = object : BackgroundRemover {
            override suspend fun cutout(imagePath: String): ScratchRef = error("Объект на фото не найден")
        }
        val result = CutoutRealizer(remover).perform(imageObj(), null)
        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
        assertEquals("Объект на фото не найден", result.reason)
    }

    @Test
    fun `отделение объекта называет себя`() = runTest {
        val remover = object : BackgroundRemover {
            override suspend fun cutout(imagePath: String) = ScratchRef("/tmp/cut.png")
        }

        val heard = stagesHeard { CutoutRealizer(remover).perform(imageObj(), null) }

        assertEquals(listOf("Отделяю объект от фона"), heard)
    }

    @Test
    fun `capability accepts only images`() {
        val cap = CutoutCapability()
        assertTrue(cap.accepts(ObjectState(ObjectKind.IMAGE)))
        assertFalse(cap.accepts(ObjectState(ObjectKind.TEXT)))
    }
}

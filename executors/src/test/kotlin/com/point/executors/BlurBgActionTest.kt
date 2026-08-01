package com.point.executors

import com.point.core.flow.BackgroundRemover
import com.point.core.flow.ImageCompositor
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurBgActionTest {

    private fun imageObj() =
        PointObject("id", "image/jpeg", ScratchRef("/tmp/photo.jpg"), ObjectState(ObjectKind.IMAGE))

    @Test
    fun `composites the sharp subject over the blurred original`() = runTest {
        val remover = object : BackgroundRemover {
            override suspend fun cutout(imagePath: String) = ScratchRef("/tmp/subject.png")
        }
        var subjectSeen: String? = null
        var bgSeen: String? = null
        val compositor = object : ImageCompositor {
            override suspend fun composite(subjectPath: String, backgroundPath: String): ScratchRef {
                subjectSeen = subjectPath
                bgSeen = backgroundPath
                return ScratchRef("/tmp/out.png")
            }
            override suspend fun blur(imagePath: String) = ScratchRef("/tmp/blurred.png")
        }
        val result = BlurBgRealizer(remover, compositor).perform(imageObj(), null)
        assertTrue(result is ActionResult.Success)
        assertEquals(ObjectKind.IMAGE, (result as ActionResult.Success).result.type)
        assertEquals("image/png", result.result.mime)
        assertEquals("/tmp/subject.png", subjectSeen) // sharp subject on top
        assertEquals("/tmp/blurred.png", bgSeen) // blurred original behind
    }

    /** #288: шага правда три, и каждый — тяжёлая работа над целым кадром; слово стоит ровно перед
     *  тем вызовом, который называет, а не «примерно посередине». */
    @Test
    fun `портретное размытие рассказывает про все три шага`() = runTest {
        val remover = object : BackgroundRemover {
            override suspend fun cutout(imagePath: String) = ScratchRef("/tmp/subject.png")
        }
        val compositor = object : ImageCompositor {
            override suspend fun composite(subjectPath: String, backgroundPath: String) = ScratchRef("/tmp/out.png")
            override suspend fun blur(imagePath: String) = ScratchRef("/tmp/blurred.png")
        }

        val heard = stagesHeard { BlurBgRealizer(remover, compositor).perform(imageObj(), null) }

        assertEquals(listOf("Отделяю объект от фона", "Размываю фон", "Собираю снимок"), heard)
    }

    @Test
    fun `a segmentation failure is recoverable`() = runTest {
        val remover = object : BackgroundRemover {
            override suspend fun cutout(imagePath: String): ScratchRef = error("Объект на фото не найден")
        }
        val compositor = object : ImageCompositor {
            override suspend fun composite(subjectPath: String, backgroundPath: String) = ScratchRef("/x")
            override suspend fun blur(imagePath: String) = ScratchRef("/x")
        }
        val result = BlurBgRealizer(remover, compositor).perform(imageObj(), null)
        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }
}

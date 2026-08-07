package com.point.executors

import com.point.core.flow.BackgroundRemover
import com.point.core.flow.CollectionContent
import com.point.core.flow.ImageCompositor
import com.point.core.flow.ObjectStore
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplaceBgActionTest {

    private fun imageObj() =
        PointObject("id", "image/jpeg", ScratchRef("/tmp/photo.jpg"), ObjectState(ObjectKind.IMAGE))

    private class FakeStore : ObjectStore {
        var ingested: String? = null
        override suspend fun ingest(sourceUri: String, mime: String): PointObject {
            ingested = sourceUri
            return PointObject("bg", mime, ScratchRef("/tmp/bg.jpg"), ObjectState(ObjectKind.IMAGE))
        }
        override suspend fun ingestMultiple(sources: List<String>): PointObject = error("unused")
        override suspend fun put(result: ResultObject): PointObject = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = CollectionContent.empty<PointObject>()
        override suspend fun readText(obj: PointObject, limit: Int): String = ""
        override suspend fun newScratchFile(extension: String) = ScratchRef("/tmp/s.$extension")
        override suspend fun clear() = Unit
    }

    private val remover = object : BackgroundRemover {
        override suspend fun cutout(imagePath: String) = ScratchRef("/tmp/subject.png")
    }

    private class FakeCompositor : ImageCompositor {
        var subject: String? = null
        var bg: String? = null
        override suspend fun composite(subjectPath: String, backgroundPath: String): ScratchRef {
            subject = subjectPath
            bg = backgroundPath
            return ScratchRef("/tmp/out.png")
        }
        override suspend fun blur(imagePath: String) = ScratchRef("/tmp/blur.png")
    }

    @Test
    fun `first tap asks for a background image`() = runTest {
        val result = ReplaceBgRealizer(FakeStore(), remover, FakeCompositor()).perform(imageObj(), null)
        assertTrue(result is ActionResult.NeedsImage)
    }

    @Test
    fun `with a picked background it cuts out and composites`() = runTest {
        val store = FakeStore()
        val compositor = FakeCompositor()
        val result = ReplaceBgRealizer(store, remover, compositor).perform(imageObj(), "content://pick/42")
        assertTrue(result is ActionResult.Success)
        assertEquals("image/png", (result as ActionResult.Success).result.mime)
        assertEquals("content://pick/42", store.ingested)
        assertEquals("/tmp/subject.png", compositor.subject)
        assertEquals("/tmp/bg.jpg", compositor.bg)
    }

    @Test
    fun `подстановка фона рассказывает про оба шага`() = runTest {
        val heard = stagesHeard {
            ReplaceBgRealizer(FakeStore(), remover, FakeCompositor()).perform(imageObj(), "content://pick/42")
        }

        assertEquals(listOf("Отделяю объект от фона", "Ставлю новый фон"), heard)
    }

    @Test
    fun `первый тап ждёт человека у выбора фона — стадии нет`() = runTest {
        val heard = stagesHeard { ReplaceBgRealizer(FakeStore(), remover, FakeCompositor()).perform(imageObj(), null) }

        assertTrue(heard.isEmpty())
    }
}

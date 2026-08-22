package com.point.executors

import com.point.core.flow.BackgroundRemover
import com.point.core.flow.CollectionContent
import com.point.core.flow.ENGINE_PREPARING
import com.point.core.flow.ImageCompositor
import com.point.core.flow.ObjectStore
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Движок фона один, а действий три (#992).
 *
 * «Убрать фон», «Размыть фон» и «Заменить фон» ходят к одному слою сегментации, поэтому слова
 * о его сбое общие: либо слово Point — и оно доходит до человека как есть, — либо ничего, и
 * тогда отказ называет само действие, которое человек нажал. Слово одного действия в двух
 * других — та же ложь, что и английский текст движка.
 */
class BackgroundEngineWordsTest {

    @Test
    fun `движок ещё готовится — все три действия зовут подождать одними словами`() = runTest {
        val said = threeActions { throw IllegalStateException(ENGINE_PREPARING) }

        assertEquals(listOf(ENGINE_PREPARING, ENGINE_PREPARING, ENGINE_PREPARING), said)
    }

    @Test
    fun `слой отказал без слов — каждое действие называет себя, а не соседа`() = runTest {
        val said = threeActions { throw IllegalStateException(null as String?) }
        val state = ObjectState(ObjectKind.IMAGE)
        val labels = listOf(
            CutoutCapability().label(state),
            BlurBgCapability().label(state),
            ReplaceBgCapability().label(state),
        )

        labels.forEachIndexed { at, label ->
            assertTrue(
                "«$label» отказало чужими словами: «${said[at]}»",
                said[at].contains(label, ignoreCase = true),
            )
        }
    }

    /** Один и тот же сбой слоя — глазами всех трёх действий, по порядку. */
    private suspend fun threeActions(fail: suspend () -> ScratchRef): List<String> {
        val remover = object : BackgroundRemover {
            override suspend fun cutout(imagePath: String): ScratchRef = fail()
        }
        val compositor = object : ImageCompositor {
            override suspend fun composite(subjectPath: String, backgroundPath: String) = ScratchRef("/tmp/out.png")
            override suspend fun blur(imagePath: String) = ScratchRef("/tmp/blur.png")
        }
        val obj = PointObject("id", "image/jpeg", ScratchRef("/tmp/photo.jpg"), ObjectState(ObjectKind.IMAGE))
        return listOf(
            CutoutRealizer(remover).perform(obj, null),
            BlurBgRealizer(remover, compositor).perform(obj, null),
            ReplaceBgRealizer(FakeStore(), remover, compositor).perform(obj, "content://pick/42"),
        ).map { (it as ActionResult.Failure).reason }
    }

    private class FakeStore : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) =
            PointObject("bg", mime, ScratchRef("/tmp/bg.jpg"), ObjectState(ObjectKind.IMAGE))
        override suspend fun ingestMultiple(sources: List<String>): PointObject = error("не нужно")
        override suspend fun put(result: ResultObject, from: PointObject?, by: CapabilityId?): PointObject =
            error("не нужно")
        override suspend fun children(collection: PointObject, limit: Int) = CollectionContent.empty<PointObject>()
        override suspend fun readText(obj: PointObject, limit: Int): String = ""
        override suspend fun newScratchFile(extension: String) = ScratchRef("/tmp/s.$extension")
        override suspend fun clear() = Unit
    }
}

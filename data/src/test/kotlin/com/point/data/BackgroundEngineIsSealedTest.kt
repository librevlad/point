package com.point.data

import com.point.core.flow.CollectionContent
import com.point.core.flow.ObjectStore
import com.point.core.model.CapabilityId
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Слой отделения от фона запечатан (#992).
 *
 * Прежде здесь проверялась функция слов, лежащая рядом со слоем, — а на пути человека
 * перехватывался ровно один вызов, сегментация. Всё остальное, что ломалось внутри, уходило
 * человеку в лицо чужим техническим текстом, и сразу через три действия: «Убрать фон»,
 * «Размыть фон», «Заменить фон» ходят одним движком.
 *
 * Проверяется сам слой на пути человека: что бы внутри ни сломалось, наружу выходят либо
 * слова Point, либо ничего — и тогда отказ называет само действие, которое нажал человек.
 */
class BackgroundEngineIsSealedTest {

    @Test
    fun `платформа сломалась внутри слоя — её текст наружу не выходит`() = runTest {
        val remover = MlKitBackgroundRemover(FakeStore())

        val thrown = runCatching { remover.cutout("/нет/такого/файла.jpg") }.exceptionOrNull()

        assertNotNull("слой обязан отказать, а не вернуть картинку", thrown)
        val said = thrown!!.message
        assertTrue(
            "из слоя вышел чужой технический текст: «$said»",
            said == null || said.any { it in 'А'..'я' },
        )
    }

    private class FakeStore : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String): PointObject = error("не нужно")
        override suspend fun ingestMultiple(sources: List<String>): PointObject = error("не нужно")
        override suspend fun put(result: ResultObject, from: PointObject?, by: CapabilityId?): PointObject =
            error("не нужно")
        override suspend fun children(collection: PointObject, limit: Int) = CollectionContent.empty<PointObject>()
        override suspend fun readText(obj: PointObject, limit: Int): String = ""
        override suspend fun newScratchFile(extension: String) = ScratchRef("/tmp/s.$extension")
        override suspend fun clear() = Unit
    }
}

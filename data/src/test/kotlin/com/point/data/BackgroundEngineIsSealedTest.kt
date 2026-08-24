package com.point.data

import com.point.core.flow.CollectionContent
import com.point.core.flow.ObjectStore
import com.point.core.flow.OwnWords
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
 * Проверяется сам слой на пути человека: что бы внутри ни сломалось, наружу выходит либо
 * объявленное слово Point ([OwnWords], #1225), либо отказ без слов — и тогда его называет
 * само действие, которое нажал человек.
 */
class BackgroundEngineIsSealedTest {

    @Test
    fun `платформа сломалась внутри слоя — наружу вышло либо слово Point, либо ничего`() = runTest {
        val remover = MlKitBackgroundRemover(FakeStore())

        val thrown = runCatching { remover.cutout("/нет/такого/файла.jpg") }.exceptionOrNull()

        assertNotNull("слой обязан отказать, а не вернуть картинку", thrown)
        assertTrue(
            "из слоя вышел не объявленный текст: «${thrown!!.message}»",
            thrown is OwnWords || thrown.message == null,
        )
    }

    @Test
    fun `слово слоя не называет чужое действие`() = runTest {
        val remover = MlKitBackgroundRemover(FakeStore())

        val thrown = runCatching { remover.cutout("/нет/такого/файла.jpg") }.exceptionOrNull()

        assertNotNull("слой обязан отказать, а не вернуть картинку", thrown)
        val said = thrown!!.message.orEmpty()

        // Движок один, действий три: слово про вырез человек читает и на «Размыть фон».
        val named = listOf("вырез", "убрать фон", "размыть", "заменить фон")
            .filter { said.contains(it, ignoreCase = true) }

        assertTrue("слой назвал действие сам: $named в «$said»", named.isEmpty())
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

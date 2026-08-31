package com.point.core.flow

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * У документа спрашивают ровно столько, сколько нужно вопросу (#1241).
 *
 * Длинный PDF разбирался целиком при каждом входе в объект, после каждого действия с
 * находками и на каждую реплику разговора — секунды мёртвого времени до того, как запрос
 * вообще ушёл с телефона. В вопрос при этом уходило шестнадцать тысяч знаков.
 *
 * Норма проекта: не запускать дорогую полную цепочку понимания для каждого объекта.
 */
class PdfIsReadOnceAndNoMoreTest {

    /** Читатель, который помнит, сколько знаков у него спросили. */
    private class Reader(private val text: String) : PdfTextExtractor {
        val asked = mutableListOf<Int?>()

        override suspend fun extractText(obj: PointObject, atMost: Int?): String {
            asked += atMost
            return if (atMost == null) text else text.take(atMost)
        }
    }

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("не нужно")
        override suspend fun ingestMultiple(sources: List<String>) = error("не нужно")
        override suspend fun put(
            result: com.point.core.model.ResultObject,
            from: PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("не нужно")
        override suspend fun children(collection: PointObject, limit: Int) = error("не нужно")
        override suspend fun readText(obj: PointObject, limit: Int) = error("не нужно")
        override suspend fun newScratchFile(extension: String) = ScratchRef("/tmp/x.$extension")
        override suspend fun clear() = Unit
    }

    private val pdf = PointObject(
        id = "doc",
        mime = "application/pdf",
        uri = ScratchRef("/tmp/doc.pdf"),
        state = ObjectState(ObjectKind.PDF, setOf(Feature.HAS_TEXT)),
    )

    @Test
    fun `знание объекта спрашивает предел, а не весь документ`() = runTest {
        val reader = Reader("страница ".repeat(50_000))

        val knowledge = GraphKnowledge(store, reader)
        knowledge.textOf(pdf, limit = 20_000)

        assertEquals("документ прочитан целиком ради обрезки", listOf<Int?>(20_000), reader.asked)
    }

    @Test
    fun `кому нужен весь документ — тот его и получает`() = runTest {
        val reader = Reader("страница")

        reader.extractText(pdf, atMost = null)

        assertTrue("предел навязан тому, кто просил всё", reader.asked.single() == null)
    }
}

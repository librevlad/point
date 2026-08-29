package com.point.executors

import com.point.core.flow.ObjectStore
import com.point.core.model.CapabilityId
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Выпрямить нечем — второго захода не будет (#1041).
 *
 * Пакета обработки снимков может не быть на устройстве: ровно этим `PageScanRealizer`
 * объясняет человеку, почему «Скан» недоступен. Чтение на таком устройстве обязано
 * остаться тем, каким было, и молча: выпрямление — не действие человека, и отказываться в нём
 * не перед кем. Ответ `null` — честное «второго захода не будет», а не ошибка.
 *
 * Родную часть OpenCV на JVM не проверить, поэтому здесь проверяется только эта ветка — и
 * то, что за ней ничего не рождается: пока кадр не выпрямлен, scratch не трогается.
 */
class OpenCvStraightFrameTest {

    /** Скрипт, а не двойник: любое обращение к хранилищу здесь — уже дефект. */
    private val untouched = object : ObjectStore {
        override suspend fun newScratchFile(extension: String): ScratchRef =
            error("невыпрямленный кадр не рождает файлов")
        override suspend fun ingest(sourceUri: String, mime: String) = throw UnsupportedOperationException()
        override suspend fun ingestMultiple(sources: List<String>) = throw UnsupportedOperationException()
        override suspend fun put(result: ResultObject, from: PointObject?, by: CapabilityId?) =
            throw UnsupportedOperationException()
        override suspend fun children(collection: PointObject, limit: Int) =
            com.point.core.flow.CollectionContent.empty<PointObject>()
        override suspend fun readText(obj: PointObject, limit: Int) = ""
        override suspend fun clear() {}
    }

    @Test
    fun `без пакета обработки снимков кадр не выпрямляется и ничего не рождает`() = runTest {
        assertNull(OpenCvStraightFrame(untouched).of("/tmp/shot.jpg"))
    }
}

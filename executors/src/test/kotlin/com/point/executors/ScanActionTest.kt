package com.point.executors

import com.point.core.flow.ObjectStore
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * «Скан» — фильтровый тир (grayscale + Otsu), запасной для того же пузырька, что и OpenCV-скан.
 *
 * #288: у способности «Скан» ДВЕ реализации, и `OpenCvScanRealizer` идёт первым, рассказывая о
 * себе. На восстановимом отказе (пакета OpenCV нет, кадр не дался) [FallbackRealizer] молча
 * переводит работу сюда — и если бы этот тир молчал, экран так и стоял бы с «Выбеливаю бумагу»
 * от сдавшегося движка, пока считает совсем другой. Поэтому проверяется главное: слово выходит
 * ДО первого касания пикселей, то есть сразу, как работа перешла сюда.
 *
 * Дальше пути нет: `android.graphics` на JVM — заглушка, декод обрывается, и остальные две стадии
 * («Свожу к чёрно-белому», «Сохраняю») проверяются живьём на телефоне. Сказано вслух, а не спрятано.
 */
class ScanActionTest {

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(result: ResultObject) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) = ScratchRef("/tmp/s.$extension")
        override suspend fun clear() = Unit
    }

    private val image = PointObject("id", "image/jpeg", ScratchRef("/tmp/photo.jpg"), ObjectState(ObjectKind.IMAGE))

    @Test
    fun `запасной тир скана называет себя раньше, чем берётся за пиксели`() = runTest {
        val heard = stagesHeard { ScanRealizer(store).perform(image, null) }

        assertEquals("Читаю снимок", heard.firstOrNull())
    }
}

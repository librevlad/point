package com.point.data

import com.point.core.flow.CodeKind
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_SOURCE_SUFFIX
import com.point.core.flow.QrReader
import com.point.core.flow.ScannedCode
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Код прочитан с кадра — так и записано (#941, #948).
 *
 * Штрихкод своё происхождение называл, а сам QR и его ссылка молчали: знание, вычитанное с
 * кадра, стояло на экране без галочки, как будто неизвестно откуда взялось. Галочка достаётся
 * прочитанному — и это ровно тот случай.
 */
class CodeReadFromFrameSaysSoTest {

    private fun reader(code: ScannedCode) = object : QrReader {
        override suspend fun decode(imagePath: String) = code.text
        override suspend fun scan(imagePath: String) = code
    }

    private val frame = PointObject(
        id = "кадр",
        mime = "image/png",
        uri = ScratchRef("/scratch/qr.png"),
        state = ObjectState(ObjectKind.IMAGE),
    )

    private fun knowledge(code: ScannedCode): Map<String, String> {
        val done = runBlocking { QrInvestigationRealizer(reader(code)).perform(frame, null) }
        return (done as ActionResult.Done).findings?.metadata.orEmpty()
    }

    @Test fun `у QR названо, что он прочитан с кадра`() {
        val facts = knowledge(ScannedCode("Wi-Fi гостям- pointguest", CodeKind.QR))

        assertEquals(Provenance.OCR.wire, facts[META_ENTITY_PREFIX + "qr" + META_SOURCE_SUFFIX])
    }

    /**
     * Ссылка в коде — одно знание, а не два (#1119). Прежде она записывалась и ссылкой, и
     * содержимым QR, и человек видел две строки об одном.
     */
    @Test fun `ссылка из кода не заводится вторым фактом`() {
        val facts = knowledge(ScannedCode("https://point.leerio.app/privacy", CodeKind.QR))

        assertEquals(null, facts[META_ENTITY_PREFIX + "qr"])
        assertEquals("https://point.leerio.app/privacy", facts[META_ENTITY_PREFIX + "url"])
    }

    @Test fun `у ссылки из QR — тоже`() {
        val facts = knowledge(ScannedCode("https://point.leerio.app/privacy", CodeKind.QR))

        assertEquals(Provenance.OCR.wire, facts[META_ENTITY_PREFIX + "url" + META_SOURCE_SUFFIX])
    }

    @Test fun `у штрихкода — как и было`() {
        val facts = knowledge(ScannedCode("4823063113311", CodeKind.PRODUCT))

        assertEquals(Provenance.OCR.wire, facts[META_ENTITY_PREFIX + "barcode" + META_SOURCE_SUFFIX])
    }

    /**
     * Код, который сам с собой не сходится, находкой не становится (#940): на фотографии
     * автомобиля сканер «прочитал» 13821702, и он вставал на экран галочкой рядом с настоящей
     * датой съёмки. Раньше этот же код стоял в тесте выше как пример штрихкода — и тест
     * держал ровно то поведение, от которого потом отказались.
     */
    @Test fun `код, не сходящийся сам с собой, находкой не становится`() {
        val facts = knowledge(ScannedCode("13821702", CodeKind.PRODUCT))

        assertEquals(emptyMap<String, String>(), facts)
    }
}

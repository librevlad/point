package com.point.data

import com.point.core.flow.QrReader
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrInvestigationTest {

    private fun imageObj() =
        PointObject("id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE))

    @Test
    fun `flags HAS_QR and keeps the decoded payload as an understood fact`() = runTest {
        val enricher = QrInvestigationRealizer(object : QrReader { override suspend fun decode(imagePath: String) = "https://qr.example" })
        val delta = enricher.look(imageObj())
        assertTrue(Feature.HAS_QR in delta.features)
        assertEquals("https://qr.example", delta.metadata[com.point.core.flow.META_ENTITY_PREFIX + "qr"])
    }

    @Test
    fun `QR со ссылкой открывает дверь ссылки — HAS_URL и сам адрес`() = runTest {

        // Живой прогон 2026-08-09: ссылка из QR показана фактом, а «Открыть ссылку»
        // сидело в «сначала распознайте текст» — Point знал ссылку и не давал открыть.
        val enricher = QrInvestigationRealizer(object : QrReader { override suspend fun decode(imagePath: String) = "https://check.monobank.ua/p/NaXzz" })
        val delta = enricher.look(imageObj())

        assertTrue(Feature.HAS_URL in delta.features)
        assertEquals("https://check.monobank.ua/p/NaXzz", delta.metadata[com.point.core.flow.META_ENTITY_PREFIX + "url"])
    }

    @Test
    fun `QR с не-ссылкой дверь ссылки не открывает`() = runTest {
        val enricher = QrInvestigationRealizer(object : QrReader { override suspend fun decode(imagePath: String) = "WIFI:T:WPA;S:home;P:secret;;" })
        val delta = enricher.look(imageObj())

        assertFalse(Feature.HAS_URL in delta.features)
        assertEquals(null, delta.metadata[com.point.core.flow.META_ENTITY_PREFIX + "url"])
    }

    @Test
    fun `no flag when there is no QR`() = runTest {
        val enricher = QrInvestigationRealizer(object : QrReader { override suspend fun decode(imagePath: String): String? = null })
        assertTrue(enricher.look(imageObj()).features.isEmpty())
    }

    @Test
    fun `applies only to images`() {
        val enricher = QrInvestigationRealizer(object : QrReader { override suspend fun decode(imagePath: String): String? = null })
        assertTrue(QrInvestigation().accepts(ObjectState(ObjectKind.IMAGE)))
        assertFalse(QrInvestigation().accepts(ObjectState(ObjectKind.TEXT)))
    }
}

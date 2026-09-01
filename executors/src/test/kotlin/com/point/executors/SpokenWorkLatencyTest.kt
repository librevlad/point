package com.point.executors

import com.point.core.flow.WordCapability

import com.point.core.flow.capabilities.PdfCapability
import com.point.core.flow.capabilities.ArchiveCapability
import com.point.core.flow.capabilities.OfficeCapability
import com.point.core.flow.capabilities.ImageCapability
import com.point.core.flow.capabilities.DropLinkCapability
import com.point.core.flow.capabilities.OcrCapability
import com.point.core.flow.Capability
import com.point.core.flow.Latency
import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenWorkLatencyTest {

    private val speaking: List<Capability> = listOf(
        ScanCapability(),
        ScanPlusCapability(),
        PdfCapability(),
        WordCapability(),
        PagesCapability(),
        ArchiveCapability(),
        MergePdfCapability(),
        ScanPdfCapability(),
        CutoutCapability(),
        BlurBgCapability(),
        ReplaceBgCapability(),
        OcrCapability(),
        OfficeCapability(),
        SaveAllCapability(),

        RenewPeriodCapability(),

        ImageCapability(),
        DropLinkCapability(),
    )

    @Test
    fun `ни одно рассказывающее о себе действие не объявлено мгновенным`() {
        val instant = speaking.filter { it.meta.latency == Latency.INSTANT }.map { it.id.value }
        assertEquals(emptyList<String>(), instant)
    }

    @Test
    fun `«В Word» над фото — тот же движок, что «Распознать текст», и та же объявленная долгота`() {

        assertEquals(OcrCapability().meta.latency, WordCapability().meta.latency)
        assertEquals(Latency.SLOW, WordCapability().meta.latency)
    }
}

package com.point.core.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gibberish detector shared by the OCR realizer chain and the OCR enricher. Pure — JVM. */
class OcrQualityTest {

    @Test
    fun `flags Tesseract gibberish from a document photo`() {
        val garbage = "; i= © © - O = & E =. are © = E oS 2 (a9) ous © E pa ae Pl ans BS &§ я OE в > 3EE:"
        assertTrue(looksLikeOcrGarbage(garbage))
    }

    @Test
    fun `passes real recognised text through`() {
        val real = "Технологічна карта окрошки. Склад: сосиски молочні, сметана, картопля відварена, огірки свіжі."
        assertFalse(looksLikeOcrGarbage(real))
    }

    @Test
    fun `does not judge very short output`() {
        assertFalse(looksLikeOcrGarbage("© = &")) // too short — let it through, not flagged
    }
}

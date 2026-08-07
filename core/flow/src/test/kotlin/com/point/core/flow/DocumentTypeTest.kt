package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTypeTest {

    @Test
    fun `the owner's parcel screenshot is a parcel`() {
        val text = """
            Прибула у відділення
            Відділення №9: вул. Хрещатик, 1
            20 4514 9154 9395
            зберігання до 29.07
        """.trimIndent()

        assertEquals(TYPE_PARCEL, documentType(text))
    }

    @Test
    fun `one delivery word plus a waybill number is enough`() {
        assertEquals(TYPE_PARCEL, documentType("Посилка 20 4514 9154 9395"))
    }

    @Test
    fun `one delivery word on its own is not`() {

        assertNull(documentType("Отделение банка работает до 18:00"))
    }

    @Test
    fun `a waybill-shaped number on its own is not`() {

        assertNull(documentType("Рахунок 20 4514 9154 9395 сплачено"))
    }

    @Test
    fun `ordinary text is left alone`() {
        assertNull(documentType("Купить молоко, хлеб и позвонить маме"))
        assertNull(documentType(""))
    }

    @Test
    fun `russian and ukrainian screens are both recognised`() {
        assertEquals(TYPE_PARCEL, documentType("Новая почта: посылка прибыла в отделение №9"))
        assertEquals(TYPE_PARCEL, documentType("Нова пошта: посилка прибула у відділення №9"))
    }

    @Test
    fun `the tag has a word to be called by`() {
        assertEquals("Посылка", documentLabel(TYPE_PARCEL))
    }

    @Test
    fun `a tag this build does not know leaves the headline to the kind`() {

        assertNull(documentLabel("cmr"))
        assertNull(documentLabel(null))
    }

    @Test
    fun `every document type carries both a tag and a label`() {
        DOCUMENT_TYPES.forEach { (tag, label) ->
            assertTrue("tag must not be blank", tag.isNotBlank())
            assertTrue("«$tag» must have a word", label.isNotBlank())
        }
    }

    @Test
    fun `known tags cover both maps, so a classifier can name a document`() {

        assertTrue(SEMANTIC_TYPES.keys.all { it in KNOWN_SEMANTIC_TAGS })
        assertTrue(DOCUMENT_TYPES.keys.all { it in KNOWN_SEMANTIC_TAGS })
        assertTrue("cmr" !in KNOWN_SEMANTIC_TAGS)
    }

    @Test
    fun `a document type never lights a feature by itself`() {

        DOCUMENT_TYPES.keys.forEach { assertNull(SEMANTIC_TYPES[it]) }
    }
}

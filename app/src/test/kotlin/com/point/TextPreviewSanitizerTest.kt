package com.point

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPreviewSanitizerTest {

    @Test
    fun `collapses a vCard base64 photo blob but keeps the fields`() {
        val vcard = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Александр Лаврон
            item1.TEL;waid=380972905258:+380 97 290 5258
            PHOTO;BASE64:/9j/4AAQSkZJRgABAQAAAQABAAD/4gHYSUNDX1BST0ZJTEUAAQEAAAHIAAAA
            QwAABtbnRyUkdCIFhZWiAH4AABAAEAAAAAAABhY3NwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAQAA9tYAAQAAAADTLQAA
            END:VCARD
        """.trimIndent()
        val clean = sanitizeTextPreview(vcard)
        assertTrue("keeps the name", clean.contains("FN:Александр Лаврон"))
        assertTrue("keeps the phone", clean.contains("+380 97 290 5258"))
        assertTrue("marks the omission", clean.contains("…"))
        assertFalse("drops the base64 body", clean.contains("QwAABtbnRyUkdC"))
        assertFalse("drops the short tail line", clean.contains("AAAAQAA9tYAAQAAAADTLQAA"))
        assertTrue("keeps the trailer", clean.contains("END:VCARD"))
    }

    @Test
    fun `leaves ordinary prose untouched`() {
        val text = "Первая строка.\nВторая строка с ссылкой https://example.com внутри."
        assertEquals(text, sanitizeTextPreview(text))
    }
}

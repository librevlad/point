package com.point.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiveBoxTest {

    @Test fun `сохранённый ящик продолжается, а не заводится заново`() {
        val box = restoredBox("aaaaaaaaaaaaaaaaaaaaaa", "https://relay/u/aaaaaaaaaaaaaaaaaaaaaa")
        assertEquals("aaaaaaaaaaaaaaaaaaaaaa", box?.id)
        assertEquals("https://relay/u/aaaaaaaaaaaaaaaaaaaaaa", box?.link)
    }

    @Test fun `без сохранённого ящика продолжать нечего`() {
        assertNull(restoredBox(null, null))

        assertNull(restoredBox("aaaaaaaaaaaaaaaaaaaaaa", null))
        assertNull(restoredBox(null, "https://relay/u/aaaaaaaaaaaaaaaaaaaaaa"))
        assertNull(restoredBox("", ""))
    }
}

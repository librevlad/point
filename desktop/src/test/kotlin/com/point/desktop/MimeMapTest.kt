package com.point.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

/** Desktop only needs "file name → mime" — classification itself is the shared
 *  [com.point.core.flow.ObjectClassifier]. */
class MimeMapTest {

    @Test
    fun `common extensions map to their mime`() {
        assertEquals("image/jpeg", mimeFor("photo.JPG"))
        assertEquals("image/png", mimeFor("shot.png"))
        assertEquals("application/pdf", mimeFor("doc.pdf"))
        assertEquals("text/plain", mimeFor("readme.txt"))
        assertEquals("text/markdown", mimeFor("notes.md"))
        assertEquals("application/zip", mimeFor("pack.zip"))
    }

    @Test
    fun `unknown extension is a binary stream`() {
        assertEquals("application/octet-stream", mimeFor("data.xyz"))
        assertEquals("application/octet-stream", mimeFor("noext"))
    }

    @Test
    fun `extFor inverts common mimes`() {
        assertEquals("jpg", extFor("image/jpeg"))
        assertEquals("png", extFor("image/png"))
        assertEquals("pdf", extFor("application/pdf"))
        assertEquals("txt", extFor("text/plain"))
        assertEquals("bin", extFor("application/octet-stream"))
    }
}

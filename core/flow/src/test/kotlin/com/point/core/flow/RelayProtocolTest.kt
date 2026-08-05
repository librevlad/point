package com.point.core.flow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Кадр объекта (мета + сырые байты) собирается до того, как его запечатает RelayCrypto.
 *  Чистый кодек — ездит туда и обратно на любой стороне. */
class RelayProtocolTest {

    @Test
    fun `frame round-trips metadata and raw binary bytes`() {
        val meta = mapOf("name" to "чек.jpg", "mime" to "image/jpeg", "entity.phone" to "+380")
        val bytes = byteArrayOf(0, 1, 2, 10, 13, -1, -128, 127)
        val frame = decodePcFrame(encodePcFrame(meta, bytes))
        assertEquals(meta, frame.meta)
        assertArrayEquals(bytes, frame.bytes)
    }

    @Test
    fun `frame handles empty meta and empty bytes`() {
        val frame = decodePcFrame(encodePcFrame(emptyMap(), ByteArray(0)))
        assertEquals(emptyMap<String, String>(), frame.meta)
        assertEquals(0, frame.bytes.size)
    }
}

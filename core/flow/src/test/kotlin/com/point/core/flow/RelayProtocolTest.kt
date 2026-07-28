package com.point.core.flow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The relay half of the pairing/protocol (#161 v2): the QR carries an optional relay URL, and the
 *  object is framed (meta + raw bytes) before RelayCrypto seals it. Pure — round-trips on any side. */
class RelayProtocolTest {

    @Test
    fun `pairing QR round-trips the relay url`() {
        val p = PcPairing("192.168.1.242", 8391, "tok123", relay = "https://35.185.31.106:8443")
        val back = parsePcPairing(p.qrPayload())
        assertEquals(p, back)
        assertEquals("https://35.185.31.106:8443", back!!.relay)
    }

    @Test
    fun `a legacy QR without a relay parses with relay null`() {
        val back = parsePcPairing("point-pc://192.168.1.242:8391/tok123")
        assertEquals(PcPairing("192.168.1.242", 8391, "tok123"), back)
        assertNull(back!!.relay)
    }

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

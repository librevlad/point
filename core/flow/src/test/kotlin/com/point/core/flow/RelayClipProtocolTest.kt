package com.point.core.flow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay clipboard protocol (#161 «общий буфер» через релей): a clip message is a [PcFrame] whose
 * meta carries the [ClipRelay.KIND] (push / pull / reply) and — when there is content — its mime and
 * name; the bytes are the payload. A pull *request* and an *empty* reply both carry no payload.
 */
class RelayClipProtocolTest {

    @Test
    fun `push frame round-trips a binary payload`() {
        val payload = ClipboardPayload("image/png", "shot.png", byteArrayOf(1, 2, 3, 0, -7, 127))
        val f = decodeClipFrame(encodeClipFrame(ClipRelay.PUSH, payload))
        assertEquals(ClipRelay.PUSH, f.kind)
        assertEquals("image/png", f.payload!!.mime)
        assertEquals("shot.png", f.payload!!.name)
        assertArrayEquals(byteArrayOf(1, 2, 3, 0, -7, 127), f.payload!!.bytes)
    }

    @Test
    fun `pull request carries no payload`() {
        val f = decodeClipFrame(encodeClipFrame(ClipRelay.PULL, null))
        assertEquals(ClipRelay.PULL, f.kind)
        assertNull(f.payload)
    }

    @Test
    fun `empty reply decodes to no payload`() {
        val f = decodeClipFrame(encodeClipFrame(ClipRelay.REPLY, null))
        assertEquals(ClipRelay.REPLY, f.kind)
        assertNull(f.payload)
    }

    @Test
    fun `reply round-trips a text payload including cyrillic`() {
        val f = decodeClipFrame(encodeClipFrame(ClipRelay.REPLY, ClipboardPayload.ofText("привет мир")))
        assertEquals(ClipRelay.REPLY, f.kind)
        assertTrue(f.payload!!.isText)
        assertEquals("привет мир", f.payload!!.text())
    }

    /** #272: протухший REPLY от таймаутнувшего прошлого pull неотличим от свежего без корреляции.
     *  PULL несёт reqId, REPLY его эхоит, телефон принимает только эхо своего запроса. */
    @Test
    fun `pull request and reply carry the correlation id`() {
        val request = decodeClipFrame(encodeClipFrame(ClipRelay.PULL, null, reqId = "r-42"))
        assertEquals("r-42", request.reqId)
        assertNull(request.payload)

        val reply = decodeClipFrame(encodeClipFrame(ClipRelay.REPLY, ClipboardPayload.ofText("x"), reqId = request.reqId))
        assertEquals("r-42", reply.reqId)
    }

    /** Кадр от сборки до reqId (или push, которому корреляция не нужна) декодируется с null. */
    @Test
    fun `frames without a correlation id decode to null reqId`() {
        assertNull(decodeClipFrame(encodeClipFrame(ClipRelay.PUSH, ClipboardPayload.ofText("x"))).reqId)
        assertNull(decodeClipFrame(encodeClipFrame(ClipRelay.REPLY, null)).reqId)
    }
}

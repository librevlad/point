package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #147 Continue on PC: the pairing QR and the metadata codec are a PROTOCOL shared by
 * the phone (sender) and the desktop (receiver) — pure Kotlin, tested here once.
 */
class ContinueOnPcTest {

    @Test
    fun `pairing roundtrip via the QR payload`() {
        val info = PcPairing(host = "192.168.1.42", port = 8391, token = "abc123XYZ")
        assertEquals(info, parsePcPairing(info.qrPayload()))
    }

    @Test
    fun `garbage and foreign QR payloads are rejected`() {
        assertNull(parsePcPairing("https://example.com"))
        assertNull(parsePcPairing("point-pc://noport/tok"))
        assertNull(parsePcPairing(""))
    }

    @Test
    fun `metadata codec roundtrips understanding`() {
        val meta = mapOf(
            "entity.phone" to "+380671234567",
            "name" to "receipt.jpg",
            "multi line" to "clean value",
        )
        assertEquals(meta, decodePcMeta(encodePcMeta(meta)))
    }

    @Test
    fun `metadata codec drops line breaks inside values`() {
        val decoded = decodePcMeta(encodePcMeta(mapOf("k" to "a\nb")))
        assertEquals("a b", decoded["k"])
    }

    // --- Remote PC capabilities (#80): the PC advertises its actions over the pairing ---

    @Test
    fun `caps codec roundtrips id and label in order`() {
        val caps = listOf(
            PcRemoteAction("pc-open", "Открыть на компьютере"),
            PcRemoteAction("pc-copy", "В буфер компьютера"),
        )
        assertEquals(caps, decodePcCaps(encodePcCaps(caps)))
    }

    @Test
    fun `caps codec carries kind gates and keeps the bare format for gate-free actions`() {
        val caps = listOf(
            PcRemoteAction("pc-open", "Открыть на компьютере"),
            PcRemoteAction("pc-download", "Скачать видео на ПК", kinds = setOf("URL")),
        )
        val decoded = decodePcCaps(encodePcCaps(caps))
        assertEquals(caps, decoded)
        assertEquals(setOf("URL"), decoded[1].kinds)
        assertTrue(decoded[0].kinds.isEmpty()) // empty = any kind
    }

    @Test
    fun `caps codec skips garbage lines and blank ids`() {
        val decoded = decodePcCaps("pc-open=Открыть\nмусор без разделителя\n=безид\npc-copy=Копировать")
        assertEquals(listOf(PcRemoteAction("pc-open", "Открыть"), PcRemoteAction("pc-copy", "Копировать")), decoded)
    }

    // --- Liquid pull (#161): the PC's outbox listing travels as id<TAB>b64(meta) lines ---

    @Test
    fun `outbox codec roundtrips ids and metadata`() {
        val entries = listOf(
            PcOutboxEntry(1, mapOf("name" to "чек.jpg", "mime" to "image/jpeg", "entity.phone" to "+380671234567")),
            PcOutboxEntry(3, mapOf("name" to "заметка.txt", "mime" to "text/plain")),
        )
        assertEquals(entries, decodePcOutbox(encodePcOutbox(entries)))
    }

    @Test
    fun `outbox codec survives garbage and empty input`() {
        assertEquals(emptyList<PcOutboxEntry>(), decodePcOutbox(""))
        assertEquals(emptyList<PcOutboxEntry>(), decodePcOutbox("мусор\nещё мусор"))
        val one = decodePcOutbox("не-число\tAAAA\n" + encodePcOutbox(listOf(PcOutboxEntry(7, mapOf("name" to "a")))))
        assertEquals(listOf(7), one.map { it.id })
    }
}

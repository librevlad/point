package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AtomCodecTest {

    private val layer = AtomLayer(
        atoms = listOf(
            Atom("w0", "Паринкін", Box(10f, 20f, 110f, 44f), 0.93f, "tesseract", "5.3.4", 0),
            Atom("w1", "20 4514", Box(12.5f, 50.25f, 90f, 70f), 0.41f, "tesseract", "5.3.4", 0),
            Atom("w2", "с\tтабом и\nпереводом", Box(0f, 0f, 1f, 1f), 1f),
        ),
        readerText = "Паринкін\n20 4514",
        transform = FrameTransform(sample = 2, rotationDegrees = 90, uprightWidth = 1024, uprightHeight = 768),
    )

    @Test
    fun `encode-decode round-trips atoms, provenance, transform and text`() {
        val decoded = AtomCodec.decode(AtomCodec.encode(layer))

        assertEquals(layer.atoms, decoded.atoms)
        assertEquals(layer.transform, decoded.transform)
        assertEquals(layer.text, decoded.text)
    }

    @Test
    fun `a layer without transform decodes to null transform`() {
        val decoded = AtomCodec.decode(AtomCodec.encode(AtomLayer(layer.atoms)))
        assertNull(decoded.transform)
        assertEquals(layer.atoms, decoded.atoms)
    }

    @Test
    fun `a geometry-only layer does not fabricate reader text`() {
        val decoded = AtomCodec.decode(AtomCodec.encode(AtomLayer(layer.atoms)))
        assertNull(decoded.readerText)

        assertEquals(AtomLayer(layer.atoms).text, decoded.text)
    }

    @Test
    fun `причина неполноты переживает encode-decode, а её отсутствие не выдумывается`() {
        val cut = AtomLayer(layer.atoms, incomplete = INCOMPLETE_TIMEOUT)
        assertEquals(INCOMPLETE_TIMEOUT, AtomCodec.decode(AtomCodec.encode(cut)).incomplete)
        assertNull(AtomCodec.decode(AtomCodec.encode(layer)).incomplete)
    }

    @Test
    fun `увеличение кадра переживает encode-decode`() {
        val enlarged = AtomLayer(
            layer.atoms,
            transform = FrameTransform(sample = 1, uprightWidth = 3000, uprightHeight = 2250, upscale = 3),
        )

        assertEquals(3, AtomCodec.decode(AtomCodec.encode(enlarged)).transform?.upscale)
    }

    @Test
    fun `дамп без поля увеличения читается как неувеличенный`() {
        val old = "#point-atoms v1\n#transform sample=2 rotation=90 w=1024 h=768\n"

        assertEquals(1, AtomCodec.decode(old).transform?.upscale)
    }

    @Test
    fun `a corrupted dump fails loudly`() {
        assertThrows(IllegalArgumentException::class.java) { AtomCodec.decode("не дамп вовсе") }
        assertThrows(IllegalArgumentException::class.java) {
            AtomCodec.decode("#point-atoms v1\nw0\tслишком\tмало\tполей")
        }
    }
}

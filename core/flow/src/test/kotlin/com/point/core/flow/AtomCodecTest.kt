package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Кодек — это дорога дословных фикстур с устройства (#257): дамп на A34 и загрузка в тестах
 * обязаны сойтись бит в бит, иначе «дословный вывод устройства» по пути превратится в сочинённый.
 * Синтетические значения здесь легитимны: проверяется сериализация, а не правило движка.
 */
class AtomCodecTest {

    private val layer = AtomLayer(
        atoms = listOf(
            Atom("w0", "Паринкін", Box(10f, 20f, 110f, 44f), 0.93f, "tesseract", "5.3.4", 0),
            Atom("w1", "20 4514", Box(12.5f, 50.25f, 90f, 70f), 0.41f, "tesseract", "5.3.4", 0),
            Atom("w2", "с\tтабом и\nпереводом", Box(0f, 0f, 1f, 1f), 1f), // синтетика: reader пуст
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

    /** Повреждённая фикстура падает громко: тихий пропуск строки превратил бы битый дамп в
     *  зелёный тест — ровно та тихая ложь, от которой слой атомов лечит. */
    @Test
    fun `a corrupted dump fails loudly`() {
        assertThrows(IllegalArgumentException::class.java) { AtomCodec.decode("не дамп вовсе") }
        assertThrows(IllegalArgumentException::class.java) {
            AtomCodec.decode("#point-atoms v1\nw0\tслишком\tмало\tполей")
        }
    }
}

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

    /** Дамп дословен: слой без текста движка кодируется БЕЗ секции #readerText — иначе сборка по
     *  полосам приписалась бы движку, и «дословный вывод устройства» стал бы сочинённым. */
    @Test
    fun `a geometry-only layer does not fabricate reader text`() {
        val decoded = AtomCodec.decode(AtomCodec.encode(AtomLayer(layer.atoms)))
        assertNull(decoded.readerText)
        // Пересборка воспроизводима из атомов той же функцией — text совпадает без фиксации.
        assertEquals(AtomLayer(layer.atoms).text, decoded.text)
    }

    /** Причина неполноты — часть дословного дампа (#262): фикстура отрезанного по времени чтения
     *  без пометки выдала бы огрызок за всё, что движок увидел на кадре. */
    @Test
    fun `причина неполноты переживает encode-decode, а её отсутствие не выдумывается`() {
        val cut = AtomLayer(layer.atoms, incomplete = INCOMPLETE_TIMEOUT)
        assertEquals(INCOMPLETE_TIMEOUT, AtomCodec.decode(AtomCodec.encode(cut)).incomplete)
        assertNull(AtomCodec.decode(AtomCodec.encode(layer)).incomplete)
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

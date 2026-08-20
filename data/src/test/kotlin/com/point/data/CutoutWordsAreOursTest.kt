package com.point.data

import com.google.mlkit.common.MlKitException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Слова Point вместо текста ML Kit (#992).
 *
 * При первом «Убрать фон» модуль сегментации ещё качается, и человеку показывался английский
 * текст вендора как отказ. Случай различается по коду исключения, не по тексту: модуль качается —
 * своё слово и приглашение попробовать снова; любой другой вендорский отказ — общее слово,
 * а чужой текст остаётся в журнале (#686).
 */
class CutoutWordsAreOursTest {

    @Test
    fun `модуль ещё качается — готовлю движок, попробуйте через минуту`() {
        assertEquals(
            "Готовлю движок выреза — попробуйте через минуту",
            segmentationFailureWords(MlKitException.UNAVAILABLE),
        )
    }

    @Test
    fun `любой другой код вендора — общее слово, не его текст`() {
        assertEquals("Убрать фон не вышло", segmentationFailureWords(MlKitException.INTERNAL))
        assertEquals("Убрать фон не вышло", segmentationFailureWords(MlKitException.NETWORK_ISSUE))
    }

    @Test
    fun `исключение без кода ML Kit — тоже общее слово`() {
        assertEquals("Убрать фон не вышло", segmentationFailureWords(null))
    }
}

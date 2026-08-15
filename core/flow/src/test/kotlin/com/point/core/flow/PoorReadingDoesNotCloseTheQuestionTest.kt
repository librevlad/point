package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Чтение, признанное мусором, вопрос не закрывает (#988).
 *
 * Сторож #694 работал: неудачное чтение не объявлялось знанием и отдавало только служебные
 * ключи — ссылку на слой слов, манеру письма, множитель увеличения. Отменял его следующий
 * слой: исход считал знанием любой ключ, кроме аннотации и состояния, и вопрос «что написано
 * на снимке» закрывался как `found` — на QR-коде без единой строки текста и даже на
 * фотографии автомобиля, где в графе стояла одна догадка о манере письма.
 *
 * `found` означает, что знание получено. Иначе вопрос больше никто не переисследует.
 */
class PoorReadingDoesNotCloseTheQuestionTest {

    @Test
    fun `служебные следы чтения знанием не считаются`() {
        val afterPoorRead = mapOf(
            META_OCR_ATOMS_REF to "/scratch/e5a7d2b8.atoms.tsv",
            META_READING_MODE to "HANDWRITTEN",
            META_READ_UPSCALE to "2",
        )

        assertEquals(
            InvestigationState.NOT_FOUND,
            investigationOutcome(afterPoorRead, afterPoorRead.keys),
        )
    }

    /** Одна догадка о манере письма — тем более не ответ на вопрос «что написано». */
    @Test
    fun `манера письма вопрос не закрывает`() {
        val guessOnly = mapOf(META_READING_MODE to "HANDWRITTEN")

        assertEquals(InvestigationState.NOT_FOUND, investigationOutcome(guessOnly, guessOnly.keys))
    }

    /** Имя, размер и тип пришли вместе с объектом — исследование их не находило. */
    @Test
    fun `данное вместе с объектом находкой не становится`() {
        val given = mapOf("name" to "qr.png", "mime" to "image/png", META_SIZE to "960")

        assertEquals(InvestigationState.NOT_FOUND, investigationOutcome(given, given.keys))
    }

    @Test
    fun `настоящее знание вопрос закрывает по-прежнему`() {
        val found = mapOf(
            META_ENTITY_PHONE to "+380671234567",
            META_OCR_TEXT_REF to "/scratch/текст.txt",
        )

        assertEquals(InvestigationState.FOUND, investigationOutcome(found, found.keys))
    }
}

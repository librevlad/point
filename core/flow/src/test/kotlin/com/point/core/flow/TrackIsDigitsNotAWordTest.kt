package com.point.core.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Трек-номер — это цифры, а не слово (#657).
 *
 * Прогон 2026-08-09: «квитанцію» и «№ 7 36ір…» ложились в знание трек-номерами, и Point
 * предлагал отследить то, чего нет. Форму проверяли только офлайн-пути, а облачный канал
 * клал кандидатов мимо проверки.
 *
 * Непрошедшее не пишется вовсе — это не спор прочтений (решение владельца в карточке).
 */
class TrackIsDigitsNotAWordTest {

    private val key = META_ENTITY_TRACK

    @Test
    fun `слово треком не становится`() {
        assertFalse(factFits(key, "квитанцію"))
        assertFalse(factFits(key, "№ 7 36ір"))
    }

    @Test
    fun `настоящий номер проходит`() {
        assertTrue(factFits(key, "59 0017 2462 6327"))
    }

    @Test
    fun `форма IBAN остаётся не треком`() {
        assertFalse(factFits(key, "UA793052990000026007031234567"))
    }

    @Test
    fun `кандидат от модели проходит ту же проверку, что и офлайн`() {
        val parsed = parseFieldCandidates("TRACK=квитанцію")

        assertTrue("облачный канал кладёт мимо проверки формы", parsed.fields[key].isNullOrEmpty())
    }

    @Test
    fun `настоящий номер от модели доходит до знания`() {
        val parsed = parseFieldCandidates("TRACK=59 0017 2462 6327")

        assertFalse(parsed.fields[key].isNullOrEmpty())
    }
}

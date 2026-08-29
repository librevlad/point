package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Слова о кадре, на котором страницы не нашли, — одни на оба пути скана (#1333).
 *
 * «Скан» берёт снимок поодиночке, «Скан в PDF» — набор снимков, и дефект у них был один:
 * ненайденная страница молча подменялась исходным кадром. Правило починки тоже одно, значит
 * и звучать оно обязано одинаково — иначе два пути назовут одно и то же по-разному и
 * разойдутся при первой правке.
 */
class WholeFrameNoteTest {

    @Test
    fun `страницу нашли везде — говорить не о чем`() {
        assertNull(wholeFrameNote(wholeFrames = 0, pages = 1))
        assertNull(wholeFrameNote(wholeFrames = 0, pages = 44))
    }

    @Test
    fun `у снимка поодиночке счёта нет — сказано про него самого`() {
        assertEquals(WHOLE_FRAME_INSTEAD_OF_PAGE, wholeFrameNote(wholeFrames = 1, pages = 1))
    }

    @Test
    fun `в наборе названо, на скольких снимках страницы не нашли`() {
        val said = wholeFrameNote(wholeFrames = 2, pages = 5).orEmpty()

        assertTrue("сколько кадров пошли целиком — не сказано: $said", "2" in said)
        assertTrue("из скольких — не сказано: $said", "5" in said)
    }

    @Test
    fun `один кадр из многих не зовётся во множественном числе`() {
        val said = wholeFrameNote(wholeFrames = 1, pages = 5).orEmpty()

        assertTrue("про один кадр сказано как про много: $said", "снимках" !in said)
        assertTrue("из скольких — не сказано: $said", "5" in said)
    }
}

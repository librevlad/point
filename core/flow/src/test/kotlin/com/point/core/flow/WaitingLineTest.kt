package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Про ожидание оба устройства говорят одно и то же (#901).
 *
 * Телефон говорил «Идёт 12 с» и после тридцати секунд честно добавлял «долгое ожидание,
 * можно отменить». Компьютер в том же месте говорил «12 секунд» и про затянувшееся ожидание
 * молчал: человек не знал, ждать ему или отменять.
 */
class WaitingLineTest {

    @Test
    fun `короткое ожидание просто считает секунды`() {
        assertTrue(waitingLine(elapsed = 5, network = true).contains("5"))
        assertTrue("короткое ожидание не должно пугать", !waitingLine(5, network = true).contains("долгое"))
    }

    @Test
    fun `затянувшееся ожидание названо вслух`() {
        val long = waitingLine(elapsed = LONG_WAIT_S + 1, network = true)

        assertTrue(long, long.contains("долгое ожидание"))
        assertTrue("не сказано, что можно отменить", long.contains("отменить"))
    }

    @Test
    fun `если отменить нельзя, про отмену не врут`() {
        val long = waitingLine(elapsed = LONG_WAIT_S + 1, network = true, cancelable = false)

        assertTrue(long, long.contains("долгое ожидание"))
        assertTrue("обещана отмена, которой нет", !long.contains("отменить"))
    }

    @Test
    fun `здешняя работа не говорит про долгое ожидание — ей некуда деться`() {
        val here = waitingLine(elapsed = LONG_WAIT_S + 30, network = false)

        assertEquals("Идёт ${LONG_WAIT_S + 30} с", here)
    }
}

package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Молчание стука называет себя (#1398).
 *
 * Владелец: «мы с тобой делали пуш-уведомления, но я не вижу, чтоб они работали». Стук был
 * настроен и уходил с сервера, а на телефоне четыре выхода из шести молчали без следа —
 * и назвать место, где он пропадает, было нельзя. Каждый выход обязан сказать, почему.
 */
class KnockMeaningTest {

    private fun entry(id: Int, vararg meta: Pair<String, String>) = PcOutboxEntry(id, mapOf(*meta))

    @Test
    fun `чужое слово в письме — молчание с названием слова`() {
        val meaning = knockMeaning(word = "hello", linked = true, waiting = emptyList())

        assertEquals(KnockMeaning.Silent("письмо не про очередь: «hello»"), meaning)
    }

    @Test
    fun `нет компьютера — молчание говорит об этом`() {
        val meaning = knockMeaning(word = KNOCK_ABOUT_OUTBOX, linked = false, waiting = null)

        assertEquals(KnockMeaning.Silent("к телефону не привязан компьютер"), meaning)
    }

    @Test
    fun `очередь не прочиталась — человека всё равно зовут`() {
        val meaning = knockMeaning(word = KNOCK_ABOUT_OUTBOX, linked = true, waiting = null)

        assertEquals(KnockMeaning.Unread, meaning)
    }

    @Test
    fun `пустая очередь и очередь из одних исходов различаются`() {
        val empty = knockMeaning(KNOCK_ABOUT_OUTBOX, linked = true, waiting = emptyList())
        val outcomes = knockMeaning(
            KNOCK_ABOUT_OUTBOX,
            linked = true,
            waiting = listOf(entry(1, PcResultFields.OUTCOME to PcResultFields.DONE)),
        )

        assertEquals(KnockMeaning.Silent("очередь компьютера пуста"), empty)
        assertTrue("исходы без объекта не названы", (outcomes as KnockMeaning.Silent).why.contains("исходы"))
    }

    @Test
    fun `просьба с объектом зовёт человека её же словами`() {
        val meaning = knockMeaning(
            KNOCK_ABOUT_OUTBOX,
            linked = true,
            waiting = listOf(entry(7, KNOCK_ACTION_LABEL to "В Excel", "name" to "накладная.jpg")),
        )

        assertEquals(KnockMeaning.Call("В Excel", "накладная.jpg"), meaning)
    }

    @Test
    fun `просьба без названия работы всё равно зовёт`() {
        val meaning = knockMeaning(KNOCK_ABOUT_OUTBOX, linked = true, waiting = listOf(entry(7, "name" to "x.pdf")))

        assertEquals(KnockMeaning.Call("сделать кое-что", "x.pdf"), meaning)
    }

    /**
     * Готовое по просьбе самого телефона — не просьба компьютера (#1416): «Компьютер просит:
     * сделать кое-что» над готовым файлом звало бы человека к работе, которой нет.
     */
    @Test
    fun `готовый результат просьбы телефона зовёт за готовым, а не к работе`() {
        val meaning = knockMeaning(
            KNOCK_ABOUT_OUTBOX,
            linked = true,
            waiting = listOf(
                entry(4, PcExecFields.HOME to "phone-obj", PcExecFields.LABEL to "В PDF", "name" to "slides.pdf"),
            ),
        )

        assertEquals(KnockMeaning.Ready("В PDF", "slides.pdf"), meaning)
    }

    @Test
    fun `просьба компьютера с домом объекта остаётся просьбой`() {
        val meaning = knockMeaning(
            KNOCK_ABOUT_OUTBOX,
            linked = true,
            waiting = listOf(
                entry(
                    5, PcExecFields.HOME to "pc-obj", PcExecFields.REQUEST to "r-1",
                    KNOCK_ACTION_LABEL to "Распознать", "name" to "фото.jpg",
                ),
            ),
        )

        assertEquals(KnockMeaning.Call("Распознать", "фото.jpg"), meaning)
    }
}

package com.point

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Разбор входящего intent — чистая функция без единого Android-типа, поэтому судится на JVM.
 * Двери (Share, «Открыть с помощью», правый клик по тексту) отличаются только тем, что кладут
 * в эти аргументы.
 */
class IncomingTest {

    @Test
    fun `шаринг файла — объект по ссылке и своим типом`() {
        val incoming = incomingOf(
            action = "android.intent.action.SEND",
            type = "image/jpeg",
            data = null,
            stream = "content://media/1",
            text = null,
            streams = emptyList(),
        )
        assertEquals(Incoming.Single("content://media/1", "image/jpeg"), incoming)
    }

    @Test
    fun `шаринг текста без файла — тело, а не ссылка`() {
        val incoming = incomingOf(
            action = "android.intent.action.SEND",
            type = "text/plain",
            data = null,
            stream = null,
            text = "привет",
            streams = emptyList(),
        )
        assertEquals(Incoming.Body("привет"), incoming)
    }

    @Test
    fun `шаринг ни с чем — ничего, а не пустой объект`() {
        val incoming = incomingOf(
            action = "android.intent.action.SEND",
            type = "text/plain",
            data = null,
            stream = null,
            text = "",
            streams = emptyList(),
        )
        assertNull(incoming)
    }

    @Test
    fun `множественный шаринг — список ссылок`() {
        val incoming = incomingOf(
            action = "android.intent.action.SEND_MULTIPLE",
            type = "image/jpeg",
            data = null,
            stream = null,
            text = null,
            streams = listOf("content://media/1", "content://media/2"),
        )
        assertEquals(Incoming.Many(listOf("content://media/1", "content://media/2")), incoming)
    }

    @Test
    fun `открыть с помощью — объект берётся из data, а не из потока`() {
        val incoming = incomingOf(
            action = "android.intent.action.VIEW",
            type = "application/pdf",
            data = "content://downloads/7",
            stream = null,
            text = null,
            streams = emptyList(),
        )
        assertEquals(Incoming.Single("content://downloads/7", "application/pdf"), incoming)
    }

    @Test
    fun `тип неизвестен — общий тип вместо падения`() {
        val incoming = incomingOf(
            action = "android.intent.action.VIEW",
            type = null,
            data = "content://downloads/7",
            stream = null,
            text = null,
            streams = emptyList(),
        )
        assertEquals(Incoming.Single("content://downloads/7", DEFAULT_MIME), incoming)
    }

    @Test
    fun `открыть с помощью — тип от системы важнее пустого типа intent`() {
        // Дверь спрашивает тип у ContentResolver, когда intent молчит, и передаёт его сюда.
        val incoming = incomingOf(
            action = "android.intent.action.VIEW",
            type = "application/zip",
            data = "content://downloads/9",
            stream = null,
            text = null,
            streams = emptyList(),
        )
        assertEquals(Incoming.Single("content://downloads/9", "application/zip"), incoming)
    }

    @Test
    fun `чужое действие — ничего`() {
        val incoming = incomingOf(
            action = "android.intent.action.MAIN",
            type = null,
            data = null,
            stream = null,
            text = null,
            streams = emptyList(),
        )
        assertNull(incoming)
    }
}

package com.point.desktop

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Peek — собственная плашка Point, не системное уведомление: прибыло с телефона →
 * высветилась, клик — вылезло окошко на этом объекте, само гаснет по сроку.
 * Пока компакт на экране, peek не нужен — человек и так видит прибытие.
 */
class PeekTest {

    private fun item(id: String = "a") = InboxItem(
        PointObject(id, "text/plain", ScratchRef("/tmp/$id.txt"), ObjectState(ObjectKind.TEXT)),
        receivedAt = 0L,
    )

    @Test
    fun `прибытие при скрытом компакте показывает плашку`() {
        val peek = PeekState(now = { 100L })

        peek.arrived(item(), compactVisible = false)

        assertEquals("a", peek.current()?.obj?.id)
    }

    @Test
    fun `компакт на экране — плашка не выскакивает`() {
        val peek = PeekState(now = { 100L })

        peek.arrived(item(), compactVisible = true)

        assertNull(peek.current())
    }

    @Test
    fun `плашка сама гаснет по сроку`() {
        var clock = 0L
        val peek = PeekState(now = { clock })
        peek.arrived(item(), compactVisible = false)

        clock = PEEK_LIFETIME_MS - 1
        assertEquals("a", peek.current()?.obj?.id)

        clock = PEEK_LIFETIME_MS + 1
        assertNull(peek.current())
    }

    @Test
    fun `новое прибытие сменяет плашку и продлевает срок`() {
        var clock = 0L
        val peek = PeekState(now = { clock })
        peek.arrived(item("a"), compactVisible = false)

        clock = PEEK_LIFETIME_MS - 100
        peek.arrived(item("b"), compactVisible = false)
        clock += 200

        assertEquals("b", peek.current()?.obj?.id)
    }

    @Test
    fun `клик забирает объект и гасит плашку`() {
        val peek = PeekState(now = { 0L })
        peek.arrived(item(), compactVisible = false)

        val opened = peek.take()

        assertEquals("a", opened?.obj?.id)
        assertNull(peek.current())
    }
}

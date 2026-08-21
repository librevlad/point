package com.point.desktop

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Явный зов из проводника поднимает окно и показывает объект (#1019, вариант B).
 *
 * Прежде «Открыть в Point» делал ровно `compactVisible = true`: скрытое окно
 * показывалось, но не поднималось, а видимое, погребённое под чужими окнами,
 * не менялось вообще. Холодный старт с файлом выходил на список (DSK-001).
 */
class RaiseOnCallTest {

    private fun item(id: String) = InboxItem(
        PointObject(id, "text/plain", ScratchRef("/tmp/$id.txt"), ObjectState(ObjectKind.TEXT)),
        receivedAt = 0L,
    )

    @Test
    fun `без зова окно не дёргается`() {
        assertEquals(0, RaiseSignal().calls.value)
    }

    @Test
    fun `каждый явный зов — ровно один подъём`() {
        val raise = RaiseSignal()

        raise.call()
        assertEquals(1, raise.calls.value)

        raise.call()
        raise.call()
        assertEquals(3, raise.calls.value)
    }

    /** Уже видимое, но погребённое окно тоже поднимается: сигнал — счётчик, не Boolean. */
    @Test
    fun `повторный зов при видимом окне не теряется`() {
        val raise = RaiseSignal()
        raise.call()
        val alreadyVisible = raise.calls.value

        raise.call()

        assertTrue(
            "второй зов слился с первым — погребённое окно не поднимется",
            raise.calls.value != alreadyVisible,
        )
    }

    @Test
    fun `холодный старт с файлом открывает сам объект — верх списка`() {
        assertEquals("b", coldStartObject(listOf(item("a"), item("b"))))
    }

    @Test
    fun `холодный старт без файла остаётся на списке`() {
        assertNull(coldStartObject(emptyList()))
    }
}

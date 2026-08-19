package com.point.desktop

import com.point.core.flow.META_ORIGIN_ID
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тот же объект, присланный второй раз, — возврат к нему, а не вторая копия (#1027).
 *
 * Тем же правилом, что вход в открытый объект на телефоне (#1110): тождество даёт
 * происхождение — письмо помнит, чей это объект. Знание прежнего приезда не теряется.
 */
class SameObjectComesBackToItselfTest {

    private fun state() = DesktopState(
        DesktopRegistry(emptySet()),
        DesktopResolver(emptySet()),
        clipboard = { },
    )

    private fun item(id: String, origin: String, vararg facts: Pair<String, String>) = InboxItem(
        PointObject(
            id,
            "text/plain",
            ScratchRef("/tmp/$id"),
            ObjectState(ObjectKind.TEXT),
            metadata = mapOf(META_ORIGIN_ID to origin, "name" to "текст.txt") + facts,
        ),
    )

    @Test fun `повторная отправка не плодит второй копии, знание сливается`() {
        val state = state()
        state.onReceived(item("first", origin = "phone-obj", "entity.phone" to "+380671234567"))
        state.onReceived(item("second", origin = "phone-obj", "entity.email" to "a@b.c"))

        assertEquals("вторая копия того же объекта", 1, state.items.value.size)
        val kept = state.items.value.single().obj
        assertEquals("знание первого приезда потеряно", "+380671234567", kept.metadata["entity.phone"])
        assertEquals("знание второго приезда потеряно", "a@b.c", kept.metadata["entity.email"])
    }

    @Test fun `разные объекты остаются разными`() {
        val state = state()
        state.onReceived(item("first", origin = "obj-a"))
        state.onReceived(item("second", origin = "obj-b"))

        assertEquals(2, state.items.value.size)
    }
}

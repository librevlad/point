package com.point.desktop

import com.point.core.flow.PcRemoteAction
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopStateTest {

    private fun state() = DesktopState(
        registry = DesktopRegistry(emptySet()),
        resolver = DesktopResolver(emptySet()),
        clipboard = { },
    )

    private fun item(kind: ObjectKind, mime: String) =
        InboxItem(PointObject("id", mime, ScratchRef("/tmp/объект"), ObjectState(kind)))

    @Test
    fun `недоступное действие телефона видно с причиной, а не скрыто`() {
        // Аудит, блок 2.3 (PC5): раньше недоступное исчезало молча — телефон в зеркальной
        // ситуации показывает причину. Теперь виден весь список, недоступное — с причиной.
        val s = state()
        s.setPhoneCaps(
            listOf(
                PcRemoteAction("call", "Позвонить", kinds = setOf("TEXT")),
                PcRemoteAction("event", "Создать событие", kinds = setOf("TEXT"), unavailable = "нет доступа к календарю"),
            ),
        )

        val offered = s.actionsFor(item(ObjectKind.TEXT, "text/plain"))

        assertEquals(listOf("Позвонить", "Создать событие"), offered.map { it.title })
        assertEquals("нет доступа к календарю", offered.last().unavailable)
    }

    @Test
    fun `недоступное без причины получает честные слова, а не пустую строку`() {
        val s = state()
        s.setPhoneCaps(listOf(PcRemoteAction("event", "Создать событие", unavailable = "")))

        val offered = s.actionsFor(item(ObjectKind.TEXT, "text/plain"))

        assertEquals("телефон сейчас не может это сделать", offered.single().unavailable)
    }

    @Test
    fun `доступное действие по-прежнему фильтруется по виду объекта`() {
        val s = state()
        s.setPhoneCaps(
            listOf(
                PcRemoteAction("call", "Позвонить", kinds = setOf("TEXT")),
                PcRemoteAction("share", "Поделиться"),
            ),
        )

        assertEquals(listOf("call", "share"), s.phoneActionsFor(item(ObjectKind.TEXT, "text/plain")).map { it.id })
        assertEquals(listOf("share"), s.phoneActionsFor(item(ObjectKind.PDF, "application/pdf")).map { it.id })
    }
}

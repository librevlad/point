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

    /** Механика связки включена: правила ниже переживут день, когда телефон научится (#785). */
    private fun state() = DesktopState(
        registry = DesktopRegistry(emptySet()),
        resolver = DesktopResolver(emptySet()),
        clipboard = { },
        phoneRunsRequests = true,
    )

    private fun item(kind: ObjectKind, mime: String) =
        InboxItem(PointObject("id", mime, ScratchRef("/tmp/объект"), ObjectState(kind)))

    @Test
    fun `загрузка кэша объявлений не молодит его на диске`() {

        // #624- при старте кэш загружался и тут же сохранялся обратно, поэтому метка
        // времени файла выглядела свежей, хотя телефон мог не объявляться неделю.
        var persisted = 0
        val s = DesktopState(
            registry = DesktopRegistry(emptySet()),
            resolver = DesktopResolver(emptySet()),
            clipboard = { },
            persistPhoneCaps = { persisted++ },
        )

        s.setPhoneCaps(listOf(PcRemoteAction("call", "Позвонить")), persist = false)
        assertEquals("чтение с диска — не новое объявление", 0, persisted)

        s.setPhoneCaps(listOf(PcRemoteAction("call", "Позвонить")))
        assertEquals("настоящее объявление телефона сохраняется", 1, persisted)
    }

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

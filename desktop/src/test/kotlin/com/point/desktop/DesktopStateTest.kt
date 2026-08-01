package com.point.desktop

import com.point.core.flow.PcRemoteAction
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Обратная сторона признака «умею, но не сейчас» (#316): телефон объявляет компьютеру свои
 * действия тем же кодеком, и недоступное обязано молчать здесь ровно так же, как молчит
 * недоступное действие ПК на телефоне. Иначе на карточке вырастет кнопка «Позвонить · телефон»,
 * которую вторая машина отработать не может, — то самое обещание, от которого лечит срез.
 *
 * Фильтр в [DesktopState.phoneActionsFor] сегодня профилактический: кнопки действий телефона на
 * ПК ещё не отрисованы. Именно поэтому он под тестом — непроверяемое обещание тихо гниёт до дня,
 * когда вызов появится, и ломается уже в руках у человека.
 */
class DesktopStateTest {

    private fun state() = DesktopState(
        registry = DesktopRegistry(emptySet()),
        resolver = DesktopResolver(emptySet()),
        clipboard = { },
    )

    private fun item(kind: ObjectKind, mime: String) =
        InboxItem(PointObject("id", mime, ScratchRef("/tmp/объект"), ObjectState(kind)))

    @Test
    fun `недоступное действие телефона не становится кнопкой на ПК`() {
        val s = state()
        s.setPhoneCaps(
            listOf(
                PcRemoteAction("call", "Позвонить", kinds = setOf("TEXT")),
                PcRemoteAction("event", "Создать событие", kinds = setOf("TEXT"), unavailable = "нет доступа к календарю"),
            ),
        )

        val offered = s.phoneActionsFor(item(ObjectKind.TEXT, "text/plain"))

        assertEquals(listOf("call"), offered.map { it.id })
    }

    /** Пустая причина ≠ доступно — то же правило, что на телефоне: «не смог объяснить» не
     *  должно тихо превратиться в «можно нажать». */
    @Test
    fun `недоступное без причины тоже не становится кнопкой`() {
        val s = state()
        s.setPhoneCaps(listOf(PcRemoteAction("event", "Создать событие", unavailable = "")))

        assertTrue(s.phoneActionsFor(item(ObjectKind.TEXT, "text/plain")).isEmpty())
    }

    /** Гейт видов остаётся гейтом: признак недоступности его не подменяет и не отменяет. */
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

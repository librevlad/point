package com.point.desktop

import com.point.core.flow.PcRemoteAction
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Человек не выбирает устройство (#597, контракт 06.08.2026, И2).
 *
 * Замер на живом наборе объявленных действий, 06.08.2026: на экране компьютера для картинки стояло
 * **32 строки** «отправить на телефон». Из них десять бессмысленных («Позвонить» на снимке,
 * «Создать событие» на документе) и четыре — двойники того, что компьютер умеет сам, включая
 * «Распознать текст» дважды подряд.
 *
 * Судится не оформление списка, а его состав: карточка просила «сгруппировать и спрятать под
 * показать ещё», но группировка не отменяет выбора устройства, а лишь делает его аккуратным.
 */
class NoDeviceChoiceTest {

    /** То, что объявил бы телефон: признаковые, видовые и общие с компьютером. */
    private val fromPhone = listOf(
        PcRemoteAction("call", "Позвонить", features = setOf(Feature.HAS_PHONE.name)),
        PcRemoteAction("email", "Написать письмо", features = setOf(Feature.HAS_EMAIL.name)),
        PcRemoteAction("scan", "Скан", kinds = setOf("IMAGE")),
        PcRemoteAction("ocr", "Распознать текст", kinds = setOf("IMAGE")),
    )

    private fun state(): DesktopState {
        val s = DesktopState(
            DesktopRegistry(com.point.core.flow.capabilities.sharedCapabilities().toSet()),
            DesktopResolver(emptySet()),
            clipboard = { },
        )
        s.setPhoneCaps(fromPhone)
        return s
    }

    private fun image(vararg features: Feature) = InboxItem(
        PointObject(
            id = "obj",
            mime = "image/png",
            uri = ScratchRef("снимок.png"),
            state = ObjectState(ObjectKind.IMAGE, features.toSet()),
        ),
    )

    @Test fun `действие, живущее признаком, снимку не предлагается`() {
        // Компьютер признаков не выставляет вовсе — значит «Позвонить» здесь не появится никогда.
        // Это верно, а не досадно: понимать объект должен тот, кто может.
        val offered = state().phoneActionsFor(image()).map { it.label }

        assertTrue("на снимке предлагают позвонить: $offered", "Позвонить" !in offered)
        assertTrue("на снимке предлагают написать письмо: $offered", "Написать письмо" !in offered)
    }

    @Test fun `когда признак есть, действие предлагается`() {
        // Обратная сторона: правило не «выкинуть признаковые», а «спрашивать признак». Понимание
        // приезжает вместе с объектом с телефона, и тогда действие законно.
        val offered = state().phoneActionsFor(image(Feature.HAS_PHONE)).map { it.label }

        assertTrue("признак есть, а действия нет: $offered", "Позвонить" in offered)
    }

    @Test fun `намерение, которое компьютер умеет сам, вторым не показывается`() {
        val offered = state().phoneActionsFor(image()).map { it.label }

        assertEquals(
            "«Распознать текст» встало на экране дважды: $offered",
            0,
            offered.count { it.contains("Распознать") },
        )
    }

    @Test fun `то, чего у компьютера нет, остаётся доступным`() {
        // Сторож против чрезмерного усердия: «Скан» умеет только телефон — камера есть у него.
        val offered = state().phoneActionsFor(image()).map { it.label }

        assertTrue("действие, которое умеет только телефон, пропало: $offered", "Скан" in offered)
    }

    @Test fun `для снимка остаётся ровно то, что к нему относится`() {
        // Итог замера: было 32 строки, из которых к картинке относились единицы.
        assertEquals(listOf("Скан"), state().phoneActionsFor(image()).map { it.label })
    }
}

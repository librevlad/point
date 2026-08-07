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

class NoDeviceChoiceTest {

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

        val offered = state().phoneActionsFor(image()).map { it.label }

        assertTrue("на снимке предлагают позвонить: $offered", "Позвонить" !in offered)
        assertTrue("на снимке предлагают написать письмо: $offered", "Написать письмо" !in offered)
    }

    @Test fun `когда признак есть, действие предлагается`() {

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

        val offered = state().phoneActionsFor(image()).map { it.label }

        assertTrue("действие, которое умеет только телефон, пропало: $offered", "Скан" in offered)
    }

    @Test fun `для снимка остаётся ровно то, что к нему относится`() {

        assertEquals(listOf("Скан"), state().phoneActionsFor(image()).map { it.label })
    }
}

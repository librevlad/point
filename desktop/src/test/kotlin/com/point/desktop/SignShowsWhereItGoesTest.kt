package com.point.desktop

import com.point.core.flow.DeviceKind
import com.point.core.flow.Resolver
import com.point.core.flow.deviceIconKey
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Знак действия показывает, куда уйдёт объект, а не где нажали (#1094, решение владельца).
 *
 * У «На телефон» в окне компьютера стоял знак компьютера — исполнителя, а у «На компьютер»
 * на телефоне — знак получателя: одно и то же действие в двух окнах читалось по-разному.
 * Сторож смотрит на список действий окна, собранный из живого набора умений компьютера, —
 * то, что человек видит, — и сверяет знак с тем, каким телефон нарисован в круге устройств.
 * Зеркало на телефоне — PcActionTest в `:executors`.
 */
class SignShowsWhereItGoesTest {

    private val window = DesktopState(
        DesktopRegistry(desktopCapabilities()),
        object : Resolver {
            override fun realizerFor(capabilityId: CapabilityId) = error("знак читается до исполнения")
        },
        clipboard = { },
    )

    private val item = InboxItem(
        PointObject("o", "text/plain", ScratchRef("/tmp/заметка.txt"), ObjectState(ObjectKind.TEXT)),
    )

    @Test fun `«На телефон» в окне компьютера носит знак телефона — получателя`() {
        val toPhone = window.actionsFor(item).single { it.bubble?.capabilityId == PcToPhoneCapability().id }

        assertEquals(
            "знак переезда — знак устройства, куда уйдёт объект",
            deviceIconKey(DeviceKind.PHONE),
            toPhone.icon,
        )
    }
}

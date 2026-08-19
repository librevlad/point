package com.point.core.flow

import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertisedActionsTest {

    private class Cap(
        id: String,
        private val takes: (ObjectState) -> Boolean,
        override val meta: CapabilityMeta = CapabilityMeta(),
        private val name: (ObjectState) -> String = { "Действие" },
    ) : Capability {
        override val id = CapabilityId(id)
        override val icon = "x"
        override fun label(state: ObjectState) = name(state)
        override fun accepts(state: ObjectState) = takes(state)
        override fun produces(state: ObjectState) = state
    }

    @Test fun `новая способность едет на вторую поверхность сама`() {
        val caps = listOf(Cap("read", { it.kind == ObjectKind.IMAGE }))

        val advertised = advertisedActions(caps)

        assertEquals(listOf("read"), advertised.map { it.id })
        assertEquals(setOf("IMAGE"), advertised.single().kinds)
    }

    @Test fun `помеченное «только у себя» не объявляется`() {

        val caps = listOf(
            Cap("to-pc", { true }, CapabilityMeta(localOnly = true)),
            Cap("open", { true }, CapabilityMeta(localOnly = true)),
            Cap("understand", { it.kind == ObjectKind.TEXT }),
        )

        assertEquals(listOf("understand"), advertisedActions(caps).map { it.id })
    }

    @Test fun `исследование не объявляется второй поверхности как действие`() {

        val caps = listOf(
            Cap("ocr", { true }, CapabilityMeta(investigation = true)),
            Cap("understand", { it.kind == ObjectKind.TEXT }),
        )

        assertEquals(listOf("understand"), advertisedActions(caps).map { it.id })
    }

    @Test fun `способность, не принимающая ничего, не объявляется`() {

        assertTrue(advertisedActions(listOf(Cap("never", { false }))).isEmpty())
    }

    @Test fun `принимающая всё объявляется без перечня видов`() {

        val advertised = advertisedActions(listOf(Cap("share", { true })))

        assertEquals(emptySet<String>(), advertised.single().kinds)
    }

    @Test fun `имя берётся у того состояния, которое действие принимает`() {

        val caps = listOf(
            Cap(
                "copy",
                { it.kind == ObjectKind.IMAGE },
                name = { if (it.kind == ObjectKind.IMAGE) "Копировать картинку" else "Копировать" },
            ),
        )

        assertEquals("Копировать картинку", advertisedActions(caps).single().label)
    }

    @Test fun `порядок тот же, что у пузырьков — по приоритету, потом по имени`() {
        val caps = listOf(
            Cap("b", { true }, CapabilityMeta(priority = 10)),
            Cap("a", { true }, CapabilityMeta(priority = 10)),
            Cap("c", { true }, CapabilityMeta(priority = 5)),
        )

        assertEquals(listOf("c", "a", "b"), advertisedActions(caps).map { it.id })
    }

    /**
     * #1174: «Открыть ссылку» принимает голый URL и любой объект с HAS_URL. Слитые в
     * общие kinds+features эти двери делали объявление всеядным — голый текст получал
     * чужое «Открыть ссылку». Двери объявляются парами «виды - признаки».
     */
    @Test fun `дверь по виду и дверь по признаку не сливаются во всеядную`() {
        val caps = listOf(
            Cap("open-url", { it.kind == ObjectKind.URL || it.has(com.point.core.model.Feature.HAS_URL) }),
        )

        val rows = advertisedActions(caps)

        with(PcActionFit) {
            assertTrue("голый текст прошёл дверь", rows.none { it.fitsObject(ObjectState(ObjectKind.TEXT)) })
            assertTrue(rows.any { it.fitsObject(ObjectState(ObjectKind.URL)) })
            assertTrue(rows.any { it.fitsObject(ObjectState(ObjectKind.TEXT, setOf(com.point.core.model.Feature.HAS_URL))) })
        }
    }

    @Test fun `обе двери переживают дорогу до второй поверхности`() {
        val caps = listOf(
            Cap("open-url", { it.kind == ObjectKind.URL || it.has(com.point.core.model.Feature.HAS_URL) }),
        )

        val there = decodePcCaps(encodePcCaps(advertisedActions(caps)))

        assertEquals(2, there.size)
        assertEquals(setOf("open-url"), there.map { it.id }.toSet())
    }

    @Test fun `действие, живущее признаком, объявляет свой признак`() {

        val caps = listOf(Cap("call", { it.has(com.point.core.model.Feature.HAS_PHONE) }))

        val advertised = advertisedActions(caps).single()

        assertEquals(setOf("HAS_PHONE"), advertised.features)
    }

    @Test fun `действие по виду объекта признаков не требует`() {
        val advertised = advertisedActions(listOf(Cap("scan", { it.kind == ObjectKind.IMAGE }))).single()

        assertEquals(emptySet<String>(), advertised.features)
    }

    @Test fun `условие признака переживает дорогу до второй поверхности`() {

        val caps = listOf(Cap("email", { it.has(com.point.core.model.Feature.HAS_EMAIL) }))

        val there = decodePcCaps(encodePcCaps(advertisedActions(caps))).single()

        assertEquals(setOf("HAS_EMAIL"), there.features)
    }

    /**
     * Действие, которое отдаёт объект другому устройству, тому устройству не рекламируется
     * (#920).
     *
     * Владелец увидел на экране: «На компьютер · телефон пока не выполняет просьбы с
     * компьютера · на телефоне». Объект уже на компьютере — предлагать отправить его туда
     * бессмысленно. Это зеркало беды, чинённой на телефоне: там компьютерное «На телефон»
     * звучало «На телефон на ПК».
     *
     * Сторожим класс, а не два случая: всё, что называет чужое устройство в своём имени,
     * рекламироваться не должно.
     */
    @Test
    fun `действие про чужое устройство не уезжает на это устройство`() {
        val caps = listOf(
            Cap("pc", { true }, CapabilityMeta(localOnly = true), { "На компьютер" }),
            Cap("understand", { true }, name = { "Понять" }),
        )

        val advertised = advertisedActions(caps)

        val aboutOtherDevice = advertised.filter { action ->
            listOf("компьютер", "телефон", " пк").any { action.label.lowercase().contains(it) }
        }
        assertTrue(
            "рекламируется действие про чужое устройство: ${aboutOtherDevice.map { it.label }}",
            aboutOtherDevice.isEmpty(),
        )
    }

}

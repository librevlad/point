package com.point.core.flow

import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Общие умения видны на обеих поверхностях (#588).
 *
 * Главное здесь — не набор полей, а правило: список **выводится** из реестра, а не пишется руками.
 * Пока он писался руками, телефон объявлял компьютеру два действия из сорока семи, и каждая новая
 * способность про вторую поверхность просто не знала.
 */
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
        // «На компьютер» с компьютера — это круг сам в себя; «Открыть» открывает ЗДЕСЬ.
        val caps = listOf(
            Cap("to-pc", { true }, CapabilityMeta(localOnly = true)),
            Cap("open", { true }, CapabilityMeta(localOnly = true)),
            Cap("understand", { it.kind == ObjectKind.TEXT }),
        )

        assertEquals(listOf("understand"), advertisedActions(caps).map { it.id })
    }

    @Test fun `способность, не принимающая ничего, не объявляется`() {
        // Иначе на второй поверхности появилась бы кнопка, которая откажет на любом объекте.
        assertTrue(advertisedActions(listOf(Cap("never", { false }))).isEmpty())
    }

    @Test fun `принимающая всё объявляется без перечня видов`() {
        // Пустой набор значит «любой вид»: перечислять все поимённо — это список, который
        // устареет в тот день, когда появится новый вид объекта.
        val advertised = advertisedActions(listOf(Cap("share", { true })))

        assertEquals(emptySet<String>(), advertised.single().kinds)
    }

    @Test fun `имя берётся у того состояния, которое действие принимает`() {
        // У части способностей имя зависит от объекта («Копировать» / «Копировать картинку»), и
        // человеку на второй поверхности нужно то, которое к его объекту и относится.
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
}

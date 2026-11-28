package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.InvestigationState
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.Resolver
import com.point.core.flow.ScanCapability
import com.point.core.flow.investigationKey
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Порядок, который видит человек на компьютере, — порядок по знанию, тот же, что у реестра и у
 * телефона (#840, #1044, #1411).
 *
 * Список действий окна пересортировывал двери заново по объявленному числу `meta.priority` и
 * этим отменял оба правила реестра: «Скан» у прочитанного снимка не поднимался (приоритет у
 * него от знания, `priority(state)`), а отвеченный вопрос снова вставал первым. Реестр при этом
 * был прав, и тест на нём оставался зелёным — поэтому проверяется показанный список.
 */
class ShownOrderFollowsKnowledgeTest {

    private class Declared(id: String, priority: Int) : Capability {
        override val id = CapabilityId(id)
        override val icon = ""
        override val meta = CapabilityMeta(priority = priority)
        override fun label(state: ObjectState) = id.value
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = state
    }

    private val nobody = object : Resolver {
        override fun realizerFor(capabilityId: CapabilityId) = error("до нажатия исполнитель не нужен")
    }

    private fun window(capabilities: Set<Capability>, linked: Boolean = false) = DesktopState(
        DesktopRegistry(capabilities),
        nobody,
        clipboard = { },
        linked = { linked },
    )

    private fun photo(vararg features: Feature, metadata: Map<String, String> = emptyMap()) = InboxItem(
        PointObject(
            "снимок",
            "image/jpeg",
            ScratchRef("/tmp/накладная.jpg"),
            ObjectState(ObjectKind.IMAGE, features.toSet()),
            metadata = metadata,
        ),
    )

    @Test
    fun `«Скан» у прочитанного снимка поднимается и в показанном списке`() {
        val st = window(desktopCapabilities())

        val plain = st.actionsFor(photo()).map { it.bubble!!.capabilityId }
        val read = st.actionsFor(photo(Feature.HAS_TEXT)).map { it.bubble!!.capabilityId }

        assertTrue(
            "знание о тексте не подняло «Скан»: было ${plain.indexOf(ScanCapability.ID)}, стало ${read.indexOf(ScanCapability.ID)}",
            read.indexOf(ScanCapability.ID) < plain.indexOf(ScanCapability.ID),
        )
    }

    @Test
    fun `свои двери показаны ровно в порядке реестра`() {
        val st = window(desktopCapabilities())

        listOf(photo(), photo(Feature.HAS_TEXT)).forEach { item ->
            assertEquals(
                st.bubblesFor(item).map { it.capabilityId },
                st.actionsFor(item).map { it.bubble!!.capabilityId },
            )
        }
    }

    /** #1411: расшифрованная запись не зовёт «Расшифровать» первым — и на экране тоже. */
    @Test
    fun `отвеченный вопрос уступает следующему шагу в показанном списке`() {
        val st = window(setOf(Declared("transcribe", priority = 1), Declared("pc-open", priority = 50)))
        val answered = photo(
            metadata = mapOf(investigationKey(CapabilityId("transcribe")) to InvestigationState.FOUND.wire),
        )

        assertEquals(listOf("transcribe", "pc-open"), st.actionsFor(photo()).map { it.title })
        assertEquals(listOf("pc-open", "transcribe"), st.actionsFor(answered).map { it.title })
    }

    /** Телефонные действия по-прежнему встают среди своих по пользе, а не хвостом (блок 2.3). */
    @Test
    fun `телефонное действие встаёт между своими по приоритету, не ломая их порядок`() {
        val st = window(setOf(Declared("scan", priority = 50), Declared("pc-open", priority = 90)), linked = true)
        st.setPhoneCaps(listOf(PcRemoteAction("call", "Позвонить", priority = 60)), persist = false)

        assertEquals(listOf("scan", "Позвонить", "pc-open"), st.actionsFor(photo()).map { it.title })
    }
}

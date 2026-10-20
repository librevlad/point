package com.point.core.ui

import com.point.core.flow.META_ENTITY_URL
import com.point.core.model.ActionYield
import com.point.core.model.Because
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Знание поднимает своё действие (#937).
 *
 * Человек делится ссылкой `https://point.leerio.app/privacy`. «Открыть» стояло одиннадцатым,
 * под свёрткой «Показать ещё 4», ниже предложения превратить ссылку в таблицу Excel — то
 * есть единственное действие, ради которого ссылкой и делятся, человек не видел.
 *
 * Теперь найденная ссылка приходит на экран своим разделом, и её продолжение стоит в нём
 * первым и подсвеченным — потому что для него у Point есть основание.
 */
class KnowledgeRaisesItsActionTest {

    private val found = Because(META_ENTITY_URL, "Ссылка", "point.leerio.app/privacy")

    private fun bubble(title: String, intent: Intent, yields: ActionYield = ActionYield.None, because: Because? = null) =
        Bubble(
            icon = "",
            title = title,
            capabilityId = CapabilityId(title),
            expectedNextState = ObjectState(ObjectKind.TEXT),
            intent = intent,
            yields = yields,
            because = because,
        )

    private val text = ObjectState(ObjectKind.TEXT)

    private fun offered(open: Bubble) = listOf(
        // Чтение обещает знание (#1101) — иначе разделу «Разобраться» нечего вести.
        bubble("Понять", Intent.UNDERSTAND, ActionYield.Same()),
        bubble("В Excel таблицу", Intent.PREPARE),
        open,
        bubble("Дать ссылку", Intent.SEND),
    )

    @Test fun `в тексте нашлась ссылка — открыть стоит первым`() {
        val open = bubble("Открыть ссылку", Intent.OPEN, because = found)

        val sections = actionSections(offered(open), text, "С текстом")

        assertEquals(SectionTopic.VALUE, sections.first().topic)
        assertEquals("point.leerio.app/privacy", sections.first().value)
        assertEquals(open, sections.first().items.single())
        assertTrue(sections.first().leads)
    }

    @Test fun `ничего такого не известно — первым понимание`() {
        val sections = actionSections(offered(bubble("Открыть ссылку", Intent.OPEN)), text, "С текстом")

        assertEquals(SectionTopic.UNDERSTAND, sections.first().topic)
    }

    @Test fun `ничего не спрятано — список тот же, изменился только порядок`() {
        val all = offered(bubble("Открыть ссылку", Intent.OPEN, because = found))

        val raised = actionSections(all, text, "С текстом").flatMap { it.items }.map { it.title }

        assertEquals(all.map { it.title }.toSet(), raised.toSet())
    }
}

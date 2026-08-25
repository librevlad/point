package com.point.core.ui

import com.point.core.model.ActionYield
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Знание поднимает своё действие (#937).
 *
 * Человек делится ссылкой `https://point.leerio.app/privacy`. «Открыть» стояло одиннадцатым,
 * под свёрткой «Показать ещё 4», ниже предложения превратить ссылку в таблицу Excel — то
 * есть единственное действие, ради которого ссылкой и делятся, человек не видел.
 *
 * Порядок не спрашивал, что Point уже знает об объекте: группы шли всегда одинаково, а
 * «воспользоваться» поднималось, только когда объект сам был значением.
 */
class KnowledgeRaisesItsActionTest {

    private fun bubble(title: String, intent: Intent, yields: ActionYield = ActionYield.None) = Bubble(
        icon = "",
        title = title,
        capabilityId = CapabilityId(title),
        expectedNextState = ObjectState(ObjectKind.TEXT),
        intent = intent,
        yields = yields,
    )

    private val open = bubble("Открыть ссылку", Intent.OPEN)

    private val offered = listOf(
        // Чтение обещает знание (#1101) — иначе группе «Извлечь» нечего вести.
        bubble("Понять", Intent.UNDERSTAND, ActionYield.Same()),
        bubble("В Excel таблицу", Intent.PREPARE),
        open,
        bubble("Дать ссылку", Intent.SEND),
    )

    @Test fun `в тексте нашлась ссылка — открыть стоит первым`() {
        val text = ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_URL))

        val sections = actionSections(offered, text, useFirst = knowsUsableValue(text))

        assertEquals(ActionGroup.USE, sections.first().group)
        assertEquals(open.capabilityId, sections.first().bubbles.single().capabilityId)
    }

    @Test fun `ничего такого не известно — порядок прежний`() {
        val text = ObjectState(ObjectKind.TEXT)

        val sections = actionSections(offered, text, useFirst = knowsUsableValue(text))

        assertEquals(ActionGroup.EXTRACT, sections.first().group)
    }

    @Test fun `ничего не спрятано — список тот же, изменился только порядок`() {
        val text = ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_PHONE))

        val raised = actionSections(offered, text, useFirst = knowsUsableValue(text))
            .flatMap { it.bubbles }.map { it.title }

        assertEquals(offered.map { it.title }.toSet(), raised.toSet())
    }
}

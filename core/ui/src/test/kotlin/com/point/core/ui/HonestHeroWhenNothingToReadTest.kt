package com.point.core.ui

import com.point.core.model.ActionYield
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Герой — честное местное, когда читать нечего (#1101, #994; решение владельца 21.08.2026).
 *
 * У архива `docs.zip` и у неизвестного `bolshoy55.bin` первым и подсвеченным стояло «AI»:
 * единственное, что попало в группу «Извлечь», — оно берёт любой файл и ничего определённого
 * не обещает. «Открыть», «Сохранить», «Поделиться» — то, что точно сработает, — лежали ниже.
 * Негодный снимок предлагал четыре способа себя прочитать; теперь чтения ему не предлагаются,
 * и вести первым становилось бы «Дать ссылку».
 */
class HonestHeroWhenNothingToReadTest {

    private fun bubble(id: String, intent: Intent, yields: ActionYield) =
        Bubble("", id, CapabilityId(id), ObjectState(ObjectKind.ZIP), intent = intent, yields = yields)

    private val ai = bubble("ai", Intent.UNDERSTAND, ActionYield.Unknown)
    private val ocr = bubble("ocr", Intent.UNDERSTAND, ActionYield.New(ObjectKind.TEXT))
    private val unzip = bubble("archive", Intent.PREPARE, ActionYield.New(ObjectKind.COLLECTION))
    private val link = bubble("drop-link", Intent.PREPARE, ActionYield.Same("ссылка на сутки"))
    private val open = bubble("open", Intent.OPEN, ActionYield.None)
    private val save = bubble("save", Intent.SEND, ActionYield.None)
    private val share = bubble("share", Intent.SEND, ActionYield.None)

    @Test fun `у архива единственное чтение — AI, и первым идёт то, что точно сработает`() {
        val sections = actionSections(listOf(ai, unzip, link, open, save, share))

        assertEquals(ActionGroup.USE, sections.first().group)
        assertEquals(open.capabilityId, sections.first().bubbles.first().capabilityId)
    }

    @Test fun `у неизвестного файла AI виден, но не героем`() {
        val sections = actionSections(listOf(ai, open, save, share))

        assertEquals(ActionGroup.USE, sections.first().group)
        assertTrue("AI пропал из списка", sections.flatMap { it.bubbles }.contains(ai))
        assertFalse("AI остался героем", sections.first().bubbles.first() == ai)
    }

    @Test fun `негодному чтений нет — ведёт Сделать, а не ссылка`() {
        val sections = actionSections(listOf(link, open, save, share))

        assertEquals(ActionGroup.USE, sections.first().group)
    }

    @Test fun `снимку, который читается, по-прежнему первым Извлечь`() {
        val sections = actionSections(listOf(ocr, ai, open, share))

        assertEquals(ActionGroup.EXTRACT, sections.first().group)
        assertEquals(ocr.capabilityId, sections.first().bubbles.first().capabilityId)
    }

    @Test fun `ничего не спрятано — меняется только порядок групп`() {
        val offered = listOf(ai, unzip, link, open, save, share)

        val shown = actionSections(offered).flatMap { it.bubbles }.map { it.capabilityId }

        assertEquals(offered.map { it.capabilityId }.toSet(), shown.toSet())
    }

    @Test fun `обещание чтения — это не «вернёт то, что попросите»`() {
        assertFalse(promisesExtraction(listOf(ai, open)))
        assertTrue(promisesExtraction(listOf(ai, ocr, open)))
        assertFalse("без чтений обещать нечего", promisesExtraction(listOf(link, open)))
    }
}

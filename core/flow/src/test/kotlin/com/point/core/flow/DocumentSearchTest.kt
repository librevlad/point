package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Границы страниц прочитанного документа — знание того же прочтения (#1014).
 */
class DocumentSearchTest {

    @Test fun `границы страниц читаются из знания и переживают испорченную пару`() {
        val meta = mapOf(META_OCR_TEXT_PAGES to "1:0, 3:140,мусор,0:5,2:-1,5:300")

        assertEquals(listOf(PageStart(1, 0), PageStart(3, 140), PageStart(5, 300)), pageStartsOf(meta))
        assertEquals(emptyList<PageStart>(), pageStartsOf(emptyMap()))
        assertEquals("1:0,3:140", pageStartsWire(listOf(PageStart(1, 0), PageStart(3, 140))))
    }

    /**
     * Границы — пометка при прочтении, а не отдельное знание: ушёл текст — ушли и они, а то
     * указывали бы в текст, которого нет. Перечитанный документ приносит свои границы, а не спор
     * с прежними.
     */
    @Test fun `границы уходят вместе с текстом и обновляются вместе с ним`() {
        val read = mapOf(META_OCR_TEXT_REF to "/a.txt", META_OCR_TEXT_PAGES to "1:0,2:10")

        assertFalse(META_OCR_TEXT_PAGES in withoutKnowledge(read, setOf(META_OCR_TEXT_REF)))

        val again = mergeKnowledge(
            read,
            mapOf(META_OCR_TEXT_REF to "/b.txt", META_OCR_TEXT_PAGES to "1:0,3:12"),
            REFRESHABLE_KNOWLEDGE,
        )
        assertEquals("1:0,3:12", again[META_OCR_TEXT_PAGES])
        assertFalse("прежние границы ушли в спор", (META_OCR_TEXT_PAGES + META_ALT_SUFFIX) in again)
    }
}

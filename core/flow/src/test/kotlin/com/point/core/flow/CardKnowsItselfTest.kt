package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Карта узнаётся по себе (#657, вторая часть; #1176): голые 16 цифр с сошедшейся
 * контрольной суммой Луна — платёжная карта, каким бы KEY модель их ни назвала.
 * «5452198100477458» из переписки кадра 03 приходило треком — и карта терялась,
 * хотя число говорит само за себя.
 */
class CardKnowsItselfTest {

    private val card = "5452198100477458"

    @Test fun `карта, названная треком, приезжает картой`() {
        val parsed = parseFieldCandidates("TRACK=$card")

        assertEquals(card, parsed.fields.getValue(META_ENTITY_PREFIX + "card").single().text)
        assertNull("трек-самозванец остался треком", parsed.fields[META_ENTITY_TRACK])
    }

    @Test fun `карта, названная квитанцией или показанием, приезжает картой`() {
        listOf("RECEIPT", "METER").forEach { key ->
            val parsed = parseFieldCandidates("$key=$card")
            assertEquals(
                "из $key карта не переехала",
                card,
                parsed.fields[META_ENTITY_PREFIX + "card"]?.single()?.text,
            )
        }
    }

    @Test fun `карта, названная телефоном, не выбрасывается — узнаётся`() {
        val parsed = parseFieldCandidates("PHONE=$card")

        assertEquals(card, parsed.fields.getValue(META_ENTITY_PREFIX + "card").single().text)
        assertNull(parsed.fields[META_ENTITY_PREFIX + "phone"])
    }

    @Test fun `настоящий трек остаётся треком — уверенности в карте нет`() {
        // 14 цифр: длина не карты, Луна не спрашивается.
        val track = "20451491549395"
        val parsed = parseFieldCandidates("TRACK=$track")

        assertEquals(track, parsed.fields.getValue(META_ENTITY_TRACK).single().text)
        assertNull(parsed.fields[META_ENTITY_PREFIX + "card"])
    }

    @Test fun `16 цифр с несошедшейся Луной картой не становятся`() {
        val parsed = parseFieldCandidates("RECEIPT=5452198100477459")

        assertNull("несошедшаяся сумма выдана за карту", parsed.fields[META_ENTITY_PREFIX + "card"])
    }

    @Test fun `карта с пробелами остаётся картой, как и была`() {
        val parsed = parseFieldCandidates("CARD=5169 3351 0965 2632")

        assertTrue(parsed.fields.getValue(META_ENTITY_PREFIX + "card").single().text.contains("5169"))
    }
}

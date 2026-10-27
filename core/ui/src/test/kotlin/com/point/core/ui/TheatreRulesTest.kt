package com.point.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила микро-театра (#209), решения владельца 01.09.2026.
 *
 * Театр — накладка над готовым экраном, и решает он немногое: что взлетает, когда кольцо
 * отвечает волной «не нашлось» и сколько театра показывать привыкшему человеку.
 */
class TheatreRulesTest {

    private val phone = TheatreValue("phone", "+380 67 123 45 67", leads = false)
    private val parcel = TheatreValue("parcel", "20450000000000", leads = true)
    private val date = TheatreValue("date", "16.04.2026", leads = false)

    @Test fun `взлетает одно — то, что открывает главное действие (D8)`() {
        assertEquals(parcel, nextFlight(emptySet(), listOf(phone, parcel, date)))
    }

    @Test fun `без главного взлетает первое из новых`() {
        assertEquals(phone, nextFlight(emptySet(), listOf(phone, date)))
    }

    @Test fun `пришедшее с вещью не взлетает — это не находка этого раза`() {
        assertNull(nextFlight(setOf("phone", "date"), listOf(phone, date)))
        assertEquals(date, nextFlight(setOf("phone"), listOf(phone, date)))
    }

    @Test fun `волна — только когда смотрели и не прибавилось ничего (D6)`() {
        assertTrue(lookedAndFoundNothing(wasLooking = true, looking = false, knownAtOpen = 2, knownNow = 2))
        assertFalse("нашлось — летит значение, а не волна", lookedAndFoundNothing(true, false, 2, 3))
        assertFalse("ещё смотрим", lookedAndFoundNothing(true, true, 2, 2))
        assertFalse("и не смотрели", lookedAndFoundNothing(false, false, 2, 2))
    }

    @Test fun `с привычкой театр короче и тише, а потом остаётся приземление (D7)`() {
        assertEquals(TheatreStage.FULL, theatreStageFor(0))
        assertEquals(TheatreStage.FULL, theatreStageFor(THEATRE_FULL_SHOWS - 1))
        assertEquals(TheatreStage.QUIET, theatreStageFor(THEATRE_FULL_SHOWS))
        assertEquals(TheatreStage.LANDING, theatreStageFor(THEATRE_QUIET_SHOWS))
        assertEquals(TheatreStage.LANDING, theatreStageFor(10_000))
    }

    @Test fun `полёт короче отклика человека и не спорит с дыханием вещи`() {
        assertTrue(MotionTokens.QUIET_FLIGHT_MS < MotionTokens.FLIGHT_MS)
        assertTrue("полёт дольше секунды задерживает взгляд на пути", MotionTokens.FLIGHT_MS < 1_000)
    }

    @Test fun `новое значение спрятано с первого кадра, а не появляется дважды`() {
        val theatre = TheatreState()
        theatre.openOnce(setOf("phone"), known = 1)

        assertFalse("пришедшее с вещью видно сразу", theatre.hidden("phone"))
        assertTrue("найденное ждёт своего полёта невидимым", theatre.hidden("parcel"))

        theatre.seen = theatre.seen + "parcel"
        assertFalse("полёт не назначен — значение на месте", theatre.hidden("parcel"))
    }

    @Test fun `без полёта новое значение не прячется`() {
        val theatre = TheatreState()
        theatre.openOnce(emptySet(), known = 0)
        theatre.flies = false

        assertFalse(theatre.hidden("parcel"))
    }
}

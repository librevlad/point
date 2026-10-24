package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Поворот листа по чтению — четвертями по часовой (#1044, кадр 23 корпуса). */
class ReadTurnTest {

    @Test
    fun `поворот чтения переводится в четверти`() {
        assertEquals(0, readQuarterTurns(mapOf(META_READ_TURN to "0")))
        assertEquals(1, readQuarterTurns(mapOf(META_READ_TURN to "90")))
        assertEquals(3, readQuarterTurns(mapOf(META_READ_TURN to "270")))
    }

    @Test
    fun `не читали или назвали не четверть — поворота нет`() {
        assertNull(readQuarterTurns(emptyMap()))
        assertNull(readQuarterTurns(mapOf(META_READ_TURN to "45")))
        assertNull(readQuarterTurns(mapOf(META_READ_TURN to "боком")))
    }

    @Test
    fun `урезанное по сроку чтение поворот помнит`() {
        val layer = AtomLayer(emptyList()).turnedBy(270)
        assertEquals(270, layer.turn)
        assertEquals(90, AtomLayer(emptyList()).turnedBy(-270).turn)
    }
}

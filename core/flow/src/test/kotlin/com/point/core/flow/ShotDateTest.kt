package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Дата съёмки — факт объекта, а не строка из EXIF (#547): человеку «12.03.2024, 14:07»,
 * а не «2024:03:12 14:07:33».
 */
class ShotDateTest {

    @Test fun `съёмочная дата читается человеком`() {
        assertEquals("12.03.2024, 14:07", shotDateLabel("2024:03:12 14:07:33"))
    }

    @Test fun `дефисы вместо двоеточий тоже понятны`() {
        assertEquals("01.09.2023, 08:05", shotDateLabel("2023-09-01 08:05:00"))
    }

    @Test fun `мусор не выдаётся за дату`() {
        assertNull(shotDateLabel(null))
        assertNull(shotDateLabel(""))
        assertNull(shotDateLabel("не дата вовсе"))
        assertNull(shotDateLabel("0000:00:00 00:00:00".take(10)))
    }
}

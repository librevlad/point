package com.point.core.ui

import com.point.core.model.ObjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingBeatTest {

    @Test
    fun `a document is read top-to-bottom, a photo diagonally`() {
        assertTrue("документ читается сверху вниз", readingSweepSpecFor(ObjectKind.PDF).vertical)
        assertTrue("текст читается сверху вниз", readingSweepSpecFor(ObjectKind.TEXT).vertical)
        assertFalse("фото — мягкий диагональный отблеск", readingSweepSpecFor(ObjectKind.IMAGE).vertical)
    }

    @Test
    fun `every kind gets a calm, sane sweep`() {
        ObjectKind.entries.forEach { kind ->
            val spec = readingSweepSpecFor(kind)
            assertTrue("$kind: свип видимо-медленный", spec.periodMs in 1_000..2_500)
            assertTrue("$kind: softness в разумных рамках", spec.softness in 0.2f..0.6f)
        }
    }

    @Test
    fun `aura is dark with no facts and warm from the first`() {
        assertEquals(0f, auraLevel(0), 0.0001f)
        assertEquals(0.55f, auraLevel(1), 0.0001f)
        assertTrue("больше фактов — теплее", auraLevel(2) > auraLevel(1))
    }

    @Test
    fun `aura ramps monotonically and saturates at one`() {
        var prev = auraLevel(0)
        (1..8).forEach { n ->
            val cur = auraLevel(n)
            assertTrue("монотонность на $n", cur >= prev)
            assertTrue("не больше 1 на $n", cur <= 1f)
            prev = cur
        }
        assertEquals("насыщается к 1", 1f, auraLevel(8), 0.0001f)
    }
}

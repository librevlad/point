package com.point.core.flow

import com.point.core.model.ObjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отказ говорит о том объекте, который человек принёс (#1033).
 *
 * `broken.pdf` из 25 байт мусора опознан как PDF и подписан «PDF», а объяснялся словами
 * «он повреждён или это не изображение».
 */
class FailureSpeaksAboutTheObjectTest {

    @Test
    fun `битый PDF не объясняется словами про изображение`() {
        val said = readerFailure("decode failed", ObjectKind.PDF)

        assertFalse("про PDF сказано «это не изображение»: $said", said.contains("изображени"))
        assertTrue("не сказано главного — файл не открылся", said.contains("не открылся"))
    }

    @Test
    fun `у снимка слова прежние`() {
        val said = readerFailure("decode failed", ObjectKind.IMAGE)

        assertTrue(said.contains("изображени"))
        assertEquals(said, readerFailure("decode failed"))
    }

    @Test
    fun `пустой документ остаётся пустым документом`() {
        assertEquals(
            readerFailure(READER_NO_PAGES),
            readerFailure(READER_NO_PAGES, ObjectKind.PDF),
        )
    }
}

package com.point.core.ui

import com.point.core.flow.FailedInvestigation
import com.point.core.flow.READER_NOT_DECODED
import com.point.core.flow.readerFailure
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #696 (охота 2026-08-10): на визитке телефон уезжал в строку «Сохранить контакт»,
 * а заголовок над остальными узлами говорил «Нашёл · 2» — человек читал два,
 * видя три значения.
 */
class FoundHeaderTest {

    @Test
    fun `часть знания показана выше — заголовок без числа`() {
        val header = foundHeader(count = 2, partShownAbove = true)
        assertTrue("not empty", header.isNotBlank())
        assertTrue("starts with Нашёл", header.startsWith("Нашёл"))
        assertTrue("no count when part shown above", !header.contains("·"))
    }

    @Test
    fun `всё знание в одном списке — число остаётся`() {
        val header = foundHeader(count = 3, partShownAbove = false)
        assertTrue("not empty", header.isNotBlank())
        assertTrue("starts with Нашёл", header.startsWith("Нашёл"))
        assertTrue("contains count", header.contains("3"))
        assertTrue("contains separator", header.contains("·"))
    }

    /**
     * #686 (охота 2026-08-10): QR-чтение и распознавание текста спотыкались об один
     * и тот же битый файл и называли это разными словами — «изображение не открылось»
     * и «не удалось прочитать страницу — decode failed». Обе ветки теперь говорят
     * одно и то же (`readerFailure`), и дедуп схлопывает совпавшие причины.
     */
    @Test
    fun `одна и та же причина от двух исследований не повторяется`() {
        val note = failedNote(
            listOf(
                FailedInvestigation(CapabilityId("qr"), null, readerFailure("not an image", ObjectKind.IMAGE)),
                FailedInvestigation(
                    CapabilityId("image-text"),
                    null,
                    readerFailure(READER_NOT_DECODED, ObjectKind.IMAGE),
                ),
            ),
        )

        assertTrue("note not null", note != null)
        val reason = readerFailure(READER_NOT_DECODED, ObjectKind.IMAGE)
        assertTrue("starts with prefix", note!!.startsWith("Не удалось посмотреть: "))
        assertTrue("contains the reason exactly once", note.split(reason).size == 2)
    }

    @Test
    fun `разные причины остаются разными строками`() {
        val note = failedNote(
            listOf(
                FailedInvestigation(CapabilityId("qr"), null, "документ не читается"),
                FailedInvestigation(
                    CapabilityId("image-text"),
                    null,
                    readerFailure(READER_NOT_DECODED, ObjectKind.IMAGE),
                ),
            ),
        )

        assertTrue("note not null", note != null)
        assertTrue("starts with prefix", note!!.startsWith("Не удалось посмотреть: "))
        assertTrue("contains first reason", note.contains("документ не читается"))
        val secondReason = readerFailure(READER_NOT_DECODED, ObjectKind.IMAGE)
        assertTrue("contains second reason", note.contains(secondReason))
        assertTrue("reasons separated by semicolon", note.contains("; "))
    }

    @Test
    fun `без причин молчим`() {
        assertNull(failedNote(emptyList()))
    }
}

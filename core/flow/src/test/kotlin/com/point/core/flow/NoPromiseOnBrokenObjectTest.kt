package com.point.core.flow

import com.point.core.model.ActionYield
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Про негодный объект обещаний не дают (#994).
 *
 * `truncated.jpg` — 400 байт от JPEG, дальше обрыв. Экран дважды сказал, что файл не
 * открылся, — и тут же первым и подсвеченным предлагал «Понять · найдёт суть, суммы, даты и
 * контакты». Причина у всех действий общая и потому сказана один раз (#874), а на её месте
 * вставало обещание результата, которого быть не может.
 */
class NoPromiseOnBrokenObjectTest {

    private val promise = ActionYield.Same("найдёт суть, суммы, даты и контакты")

    @Test
    fun `обещание не даётся там, где его нельзя сдержать`() {
        assertNull(yieldLabel(promise, unusableReason = null, promiseHolds = false))
    }

    @Test
    fun `на годном объекте обещание остаётся`() {
        assertEquals(promise.note, yieldLabel(promise))
    }

    /** Своя причина у действия — не молчание: она и говорится вместо обещания (#569, #943). */
    @Test
    fun `своя причина действия важнее обещания`() {
        assertEquals(NO_INTERNET_NOTE, yieldLabel(promise, unusableReason = NO_INTERNET_NOTE, promiseHolds = false))
    }

    @Test
    fun `битый файл без единого прочитанного значения читать нечем`() {
        val broken = ObjectState(ObjectKind.IMAGE, setOf(Feature.UNUSABLE))

        assertTrue(nothingToRead(broken, mapOf(META_UNUSABLE_REASON to "Файл не открылся")))
    }

    /**
     * Пометка о годности не сильнее знания: у снимка, чей предпросмотр не отрисовался, слова
     * со страницы могли прочитаться — тогда читать есть что.
     */
    @Test
    fun `прочитанное знание оставляет чтение осмысленным`() {
        val broken = ObjectState(ObjectKind.IMAGE, setOf(Feature.UNUSABLE))

        assertFalse(nothingToRead(broken, mapOf(META_ENTITY_PHONE to "+380671234567")))
        assertFalse(nothingToRead(ObjectState(ObjectKind.IMAGE, setOf(Feature.UNUSABLE, Feature.HAS_TEXT)), emptyMap()))
    }

    @Test
    fun `у годного объекта читать есть что всегда`() {
        assertFalse(nothingToRead(ObjectState(ObjectKind.IMAGE), emptyMap()))
    }
}

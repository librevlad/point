package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Предложение пробы на экране ключей (#477): новичок без ключа и без входа видел только «нужен
 * ключ». Обещание пробы появляется, только когда сервер сам сказал, что она есть.
 */
class AiTrialOfferTest {

    private val serverHasTrial = PointAiTrial(available = true, left = 30, trial = 30)

    @Test
    fun `кто не вошёл, видит вход ради пробы — если сервер её даёт`() {
        val offer = aiTrialOfferOf(signedIn = false, quota = null, offer = serverHasTrial)!!

        assertEquals(false, offer.signedIn)
        assertTrue(aiTrialTitle(offer).contains("30"))
    }

    @Test
    fun `сервер пробы не даёт или не ответил — обещания нет`() {
        assertNull(aiTrialOfferOf(false, null, serverHasTrial.copy(available = false)))
        assertNull(aiTrialOfferOf(false, null, null))
        assertNull(aiTrialOfferOf(true, null, serverHasTrial))
    }

    @Test
    fun `вошедший видит остаток, а не приглашение войти`() {
        val offer = aiTrialOfferOf(signedIn = true, quota = serverHasTrial.copy(left = 7), offer = null)!!

        assertTrue(offer.signedIn)
        assertTrue(aiTrialTitle(offer).contains("7"))
    }
}

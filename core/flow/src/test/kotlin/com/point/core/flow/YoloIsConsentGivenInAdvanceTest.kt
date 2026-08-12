package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Режим YOLO — это согласие, данное заранее (#795).
 *
 * Конституция §11 разрешает ровно такую форму: «согласие может быть дано заранее — выбранным
 * режимом работы». Чего она не разрешает — подразумевать его; поэтому режим включается
 * выбором, а не сам собой, и всё, что он открывает, названо здесь поимённо.
 */
class YoloIsConsentGivenInAdvanceTest {

    @Test
    fun `в режиме чтение моделями разрешено без отдельного вопроса`() {
        assertTrue(cloudAllowedIn(CloudScope.MODELS, yolo = true, remembered = false))
    }

    @Test
    fun `без режима остаётся прежний вопрос`() {
        assertFalse(cloudAllowedIn(CloudScope.MODELS, yolo = false, remembered = false))
        assertTrue(cloudAllowedIn(CloudScope.MODELS, yolo = false, remembered = true))
    }

    @Test
    fun `открытую ссылку режим не открывает — про такое спрашивают каждый раз`() {
        assertFalse(cloudAllowedIn(CloudScope.PUBLIC_LINK, yolo = true, remembered = true))
    }

    @Test
    fun `в режиме открыты все пути отправки`() {
        assertEquals(PrivacyLevel.FREE_FIRST, privacyLevelIn(yolo = true, chosen = PrivacyLevel.DEVICE_ONLY))
    }

    @Test
    fun `выбранный уровень режим не стирает — он возвращается после выключения`() {
        assertEquals(PrivacyLevel.DEVICE_ONLY, privacyLevelIn(yolo = false, chosen = PrivacyLevel.DEVICE_ONLY))
        assertEquals(PrivacyLevel.NO_TRAINING, privacyLevelIn(yolo = false, chosen = PrivacyLevel.NO_TRAINING))
    }
}

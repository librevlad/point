package com.point.desktop

import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.KeyProbe
import com.point.core.flow.KeyVerdict
import com.point.core.flow.UserAiConfig
import com.point.core.flow.keyVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.point.core.flow.probeUrl

/**
 * Ключи на компьютере устроены как на телефоне (#610, решение владельца 10.08.2026).
 *
 * Владелец: «согласовать с десктопными чтобы единообразно». Разошлись они сильно: на телефоне
 * сервис выбирается из списка, говорит, для чего он, и ключ проверяется до первого дела; на
 * компьютере стояли три голых поля без проверки и без подсказок, а отказ сервиса объяснялся
 * своим набором фраз — вплоть до адреса файла настроек вместо того, что делать.
 */
class SameKeysOnBothDevicesTest {

    private val openRouter = AI_PROVIDERS.first { it.id == "openrouter" }

    @Test
    fun `компьютер берёт сервисы из того же списка, что и телефон`() {
        assertTrue("список сервисов на компьютере оказался свой", AI_PROVIDERS.size >= 2)
        AI_PROVIDERS.forEach { provider ->
            assertTrue("сервис ${provider.id} не говорит, для чего он", provider.what.isNotBlank())
            assertTrue("сервис ${provider.id} не говорит, где взять ключ", provider.keyUrl.isNotBlank())
        }
    }

    /** Проверка стучится туда же, куда идёт работа, — и от базы, и от полного адреса. */
    @Test
    fun `адрес проверки собирается из любого вида записанного адреса`() {
        val wanted = openRouter.baseUrl + "/chat/completions"

        assertEquals(wanted, probeUrl(openRouter.baseUrl))
        assertEquals(wanted, probeUrl(wanted))
        assertEquals(wanted, probeUrl(openRouter.baseUrl + "/"))
    }

    @Test
    fun `пустой адрес не оставляет проверку без цели`() {
        assertEquals(UserAiConfig.DEFAULT.baseUrl + "/chat/completions", probeUrl("   "))
    }

    /**
     * Одно и то же положение человека называется на обоих устройствах одинаково: словарь
     * отказов один, и компьютер больше не отсылает к файлу настроек вместо действия.
     */
    @Test
    fun `отказ сервиса объясняется общими словами`() {
        val refused = keyVerdict(KeyProbe(status = 401)) as KeyVerdict.Refused

        assertTrue("отказ не говорит, что делать", refused.fix.isNotBlank())
        assertTrue("компьютер снова отсылает к файлу настроек", ".point-pc" !in refused.fix)
    }

    @Test
    fun `сработавший ключ доказывает себя ответом сервиса`() {
        val said = "готово"

        val works = keyVerdict(KeyProbe(status = 200, reply = said)) as KeyVerdict.Works

        assertEquals(said, works.reply)
    }
}

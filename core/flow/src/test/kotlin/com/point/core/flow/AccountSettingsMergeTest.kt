package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Настройки, которые едут за человеком, и как они сходятся (#610).
 *
 * Два устройства правят настройки независимо и встречаются на сервере. Правило то же, что у
 * обмена секретами между устройствами: побеждает то, что человек сделал позже, но пустое
 * чужое не стирает своё — иначе устройство, где человек ничего не вводил, обнуляло бы всё.
 */
class AccountSettingsMergeTest {

    private fun keys(vararg pairs: Pair<String, String>) = pairs.fold(UserAiKeys.NONE) { acc, (id, key) ->
        acc.with(UserAiKey(id, key))
    }

    @Test
    fun `ключ, введённый на телефоне, узнаёт компьютер`() {
        val onPhone = AccountSettings(aiKeys = keys("openrouter" to "sk-1"), at = 10)
        val onPc = AccountSettings(at = 5)

        val merged = onPc.mergedWith(onPhone)

        assertEquals("sk-1", merged.aiKeys.keyFor("openrouter"))
    }

    @Test
    fun `ключи разных сервисов складываются, а не вытесняют друг друга`() {
        val onPhone = AccountSettings(aiKeys = keys("openrouter" to "sk-1"), at = 10)
        val onPc = AccountSettings(aiKeys = keys("groq" to "gsk-2"), at = 20)

        val merged = onPc.mergedWith(onPhone)

        assertEquals("gsk-2", merged.aiKeys.keyFor("groq"))
        assertEquals("sk-1", merged.aiKeys.keyFor("openrouter"))
    }

    @Test
    fun `один и тот же сервис — побеждает то, что позже`() {
        val was = "sk-старый"
        val now = "sk-новый"
        val older = AccountSettings(aiKeys = keys("openrouter" to was), at = 10)
        val newer = AccountSettings(aiKeys = keys("openrouter" to now), at = 20)

        assertEquals(now, older.mergedWith(newer).aiKeys.keyFor("openrouter"))
        assertEquals(now, newer.mergedWith(older).aiKeys.keyFor("openrouter"))
    }

    @Test
    fun `устройство, где ничего не вводили, ничего и не стирает`() {
        val mine = AccountSettings(
            aiKeys = keys("openrouter" to "sk-1"),
            speechKey = "речь",
            privacy = PrivacyLevel.DEVICE_ONLY,
            sound = false,
            at = 10,
        )

        val merged = mine.mergedWith(AccountSettings(at = 99))

        assertEquals(mine.copy(at = 99), merged)
    }

    @Test
    fun `выбор «куда можно отправлять» едет вместе с ключами`() {
        val onPhone = AccountSettings(privacy = PrivacyLevel.DEVICE_ONLY, at = 10)

        val merged = AccountSettings(privacy = PrivacyLevel.FREE_FIRST, at = 5).mergedWith(onPhone)

        assertEquals(PrivacyLevel.DEVICE_ONLY, merged.privacy)
    }

    @Test
    fun `выключенный звук не путается с «человек не выбирал»`() {
        val off = AccountSettings(sound = false, at = 10)

        assertEquals(false, AccountSettings(at = 5).mergedWith(off).sound)
        assertNull(AccountSettings(at = 5).mergedWith(AccountSettings(at = 6)).sound)
    }

    @Test
    fun `настройки переживают дорогу строкой`() {
        val mine = AccountSettings(
            aiKeys = keys("openrouter" to "sk-1", "groq" to "gsk-2"),
            speechKey = "речь",
            ocrKey = "снимки",
            privacy = PrivacyLevel.NO_TRAINING,
            sound = true,
            at = 42,
        )

        assertEquals(mine, AccountSettings.decode(mine.encode()))
    }

    @Test
    fun `пустые настройки узнают себя и никуда не едут`() {
        assertTrue(AccountSettings(at = 7).isEmpty)
        assertTrue(!AccountSettings(sound = true).isEmpty)
    }

    /** Имя устройства и правый клик остаются на своей стороне: у них отличается сам мир. */
    @Test
    fun `своё у каждой стороны в общие настройки не попадает`() {
        val wire = AccountSettings(aiKeys = keys("openrouter" to "sk-1"), at = 1).encode()

        assertTrue("имя устройства уехало в общие настройки", "name" !in wire)
        assertTrue("правый клик уехал в общие настройки", "right.click" !in wire)
    }
}

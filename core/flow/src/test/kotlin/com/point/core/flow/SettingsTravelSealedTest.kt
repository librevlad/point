package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Настройки едут за человеком, а сервер их не читает (#610, решение владельца 10.08.2026).
 *
 * «Всё, включая ключи, но ключи в закрытом виде». Настройки едут через сервер — то есть через
 * машину, которой человек своих ключей от чужих сервисов не отдавал. Поэтому содержимое
 * запечатывается на устройстве, а ключ содержимого кладётся в конверт под публичную часть
 * каждого устройства круга: вскрыть может только устройство и никогда — сервер.
 *
 * Отдельного механизма для этого не заведено: конверт стоит на той же паре ключей, которую
 * устройство отдаёт серверу при входе в круг.
 */
class SettingsTravelSealedTest {

    private val phoneKeys = DeviceKeys.generate()

    private val pcKeys = DeviceKeys.generate()

    private val phone = CircleDevice("phone", DeviceKind.PHONE, "Пиксель", key = phoneKeys.publicKey)

    private val pc = CircleDevice("pc", DeviceKind.PC, "Рабочий ПК", key = pcKeys.publicKey)

    private val seal = SettingsSeal()

    private val mine = AccountSettings(
        aiKeys = UserAiKeys.NONE.with(UserAiKey("openrouter", "sk-очень-секретный-ключ")),
        privacy = PrivacyLevel.NO_TRAINING,
        sound = false,
        at = 1_000L,
    )

    @Test
    fun `второе устройство человека вскрывает и читает то же самое`() {
        val sealed = seal.seal(mine.encode(), listOf(phone, pc), at = mine.at)!!

        val opened = seal.open(sealed, "pc", pcKeys.privateKey)

        assertEquals(mine, AccountSettings.decode(opened!!))
    }

    @Test
    fun `запечатавшее устройство вскрывает своё же`() {
        val sealed = seal.seal(mine.encode(), listOf(phone, pc), at = mine.at)!!

        assertNotNull(seal.open(sealed, "phone", phoneKeys.privateKey))
    }

    /** Сервер видит только байты: ни ключа сервиса, ни выбранного уровня приватности. */
    @Test
    fun `в том, что уезжает на сервер, ключа не видно`() {
        val sealed = seal.seal(mine.encode(), listOf(phone, pc), at = mine.at)!!

        val onServer = sealed.encode()

        assertTrue("ключ уехал открытым текстом", "sk-очень-секретный-ключ" !in onServer)
        assertTrue("выбор человека уехал открытым", PrivacyLevel.NO_TRAINING.name !in onServer)
        assertTrue("имя сервиса уехало открытым", "openrouter" !in onServer)
    }

    @Test
    fun `чужой ключ конверта не открывает`() {
        val sealed = seal.seal(mine.encode(), listOf(phone, pc), at = mine.at)!!
        val stranger = DeviceKeys.generate()

        assertNull("чужое устройство вскрыло настройки", seal.open(sealed, "pc", stranger.privateKey))
    }

    @Test
    fun `устройству вне круга конверта нет`() {
        val sealed = seal.seal(mine.encode(), listOf(phone), at = mine.at)!!

        assertNull(seal.open(sealed, "pc", pcKeys.privateKey))
    }

    /** Отозванному устройству конверт не кладут — иначе отзыв ничего не значил бы. */
    @Test
    fun `конверты кладутся только тем, кто в круге`() {
        val sealed = seal.seal(mine.encode(), listOf(phone), at = mine.at)!!

        assertEquals(setOf("phone"), sealed.wraps.keys)
    }

    @Test
    fun `устройство без объявленной публичной части конверта не получает`() {
        val silent = CircleDevice("mute", DeviceKind.PC, "Не объявился", key = "")

        val sealed = seal.seal(mine.encode(), listOf(phone, silent), at = mine.at)!!

        assertEquals(setOf("phone"), sealed.wraps.keys)
    }

    @Test
    fun `запечатать некому — настройки не уезжают вовсе`() {
        assertNull(seal.seal(mine.encode(), emptyList(), at = mine.at))
    }

    @Test
    fun `конверт переживает дорогу через сервер строкой`() {
        val sealed = seal.seal(mine.encode(), listOf(phone, pc), at = mine.at)!!

        val back = SealedSettings.decode(sealed.encode())!!

        assertEquals(sealed, back)
        assertEquals(mine.encode(), seal.open(back, "pc", pcKeys.privateKey))
    }

    @Test
    fun `порченый конверт молчит, а не выдаёт мусор за настройки`() {
        val sealed = seal.seal(mine.encode(), listOf(phone), at = mine.at)!!
        val spoiled = sealed.copy(body = sealed.body.dropLast(4) + "AAAA")

        assertNull(seal.open(spoiled, "phone", phoneKeys.privateKey))
    }

    /** Секрет связки для настроек не годится по построению: назначения разведены. */
    @Test
    fun `секрет связки и секрет настроек — разные секреты`() {
        val forPc = DeviceKeys.sharedSecret(phoneKeys.privateKey, pcKeys.publicKey)
        val forSettings = DeviceKeys.sharedSecret(
            phoneKeys.privateKey,
            pcKeys.publicKey,
            DeviceKeys.SETTINGS_CONTEXT,
        )

        assertTrue("один и тот же секрет запечатывает и связку, и настройки", !forPc.contentEquals(forSettings))
    }
}

package com.point.core.flow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сервер везёт шифротекст, а ключ рождается у устройств (#475).
 *
 * Здесь проверяется главное обещание, которое Point даёт про сервер: «что вы пересылаете между
 * своими устройствами, сервер прочитать не может». Оно держится не словом в тексте экрана, а тем,
 * что общий секрет вычисляется двумя устройствами из их собственных ключей и на сервер не попадает
 * ни в каком виде.
 *
 * Чистая JVM, поэтому прогоняется одинаково за телефон и за компьютер.
 */
class RelayCryptoTest {

    private val phone = DeviceKeys.generate()
    private val pc = DeviceKeys.generate()
    private val stranger = DeviceKeys.generate()

    private inline fun failsToOpen(block: () -> Unit): Boolean =
        try {
            block()
            false
        } catch (e: Exception) {
            true
        }

    @Test
    fun `общий секрет одинаков с обеих сторон и сервер в нём не участвует`() {
        val mine = DeviceKeys.sharedSecret(phone.privateKey, pc.publicKey)
        val theirs = DeviceKeys.sharedSecret(pc.privateKey, phone.publicKey)

        assertArrayEquals("ECDH обязан сойтись — иначе письмо не распечатает никто", mine, theirs)
        assertEquals("ключ шифра — ровно 256 бит", 32, mine!!.size)
    }

    @Test
    fun `у другой пары устройств — другой секрет`() {
        assertNotEquals(
            DeviceKeys.sharedSecret(phone.privateKey, pc.publicKey)!!.toList(),
            DeviceKeys.sharedSecret(phone.privateKey, stranger.publicKey)!!.toList(),
        )
    }

    @Test
    fun `запечатанное этим ключом распечатывается им же`() {
        val key = DeviceKeys.sharedSecret(phone.privateKey, pc.publicKey)!!
        val plain = "Чек · 693,40 ₴".toByteArray(Charsets.UTF_8)

        assertArrayEquals(plain, RelayCrypto.open(key, RelayCrypto.seal(key, plain)))
    }

    @Test
    fun `чужой ключ письма не открывает`() {
        val key = DeviceKeys.sharedSecret(phone.privateKey, pc.publicKey)!!
        val alien = DeviceKeys.sharedSecret(stranger.privateKey, pc.publicKey)!!
        val blob = RelayCrypto.seal(key, "secret".toByteArray())

        assertTrue(failsToOpen { RelayCrypto.open(alien, blob) })
    }

    @Test
    fun `испорченное письмо не притворяется целым`() {
        val key = DeviceKeys.sharedSecret(phone.privateKey, pc.publicKey)!!
        val blob = RelayCrypto.seal(key, "secret".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()

        assertTrue(failsToOpen { RelayCrypto.open(key, blob) })
    }

    @Test
    fun `нет ключа соседа — нет и секрета, а не выдуманный ключ`() {
        // Устройство вошло сборкой без ключей или круг ещё не приехал. Молчание здесь честнее:
        // на выдуманном ключе письмо уехало бы, а распечатать его не смог бы никто.
        assertNull(DeviceKeys.sharedSecret(phone.privateKey, ""))
        assertNull(DeviceKeys.sharedSecret("", pc.publicKey))
        assertNull(DeviceKeys.sharedSecret(phone.privateKey, "не-ключ"))
    }

    @Test
    fun `в круг уезжает только открытая половина`() {
        // Закрытая половина не должна выводиться из открытой — иначе весь замысел «ключ рождается
        // у устройства» превращается в обряд.
        assertTrue(phone.publicKey.isNotBlank() && phone.privateKey.isNotBlank())
        assertTrue(phone.publicKey !in phone.privateKey)
        assertNotEquals(phone.publicKey, DeviceKeys.generate().publicKey)
    }

    @Test
    fun `общий ключ по кругу берётся тем же швом, которым им пользуется транспорт`() {
        val secrets = KeyStoreSecrets(object : DeviceKeyStore {
            override fun keys() = phone
        })

        assertArrayEquals(
            DeviceKeys.sharedSecret(phone.privateKey, pc.publicKey),
            secrets.sharedWith(LinkedPc("d-pc", "Домашний ПК", pc.publicKey)),
        )
        assertNull("компьютер без ключа — писать ему нечем", secrets.sharedWith(LinkedPc("d-pc", "ПК")))
    }
}

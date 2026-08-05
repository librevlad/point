package com.point.core.flow

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Кадр между устройствами запечатан, и сервер везёт шифротекст (#161 v2, переписано в #475).
 *
 * Ключ сюда приходит готовым — общий секрет двух устройств круга ([DeviceKeys.sharedSecret]).
 * Раньше он выводился из токена пары, и вместе с парой исчез бы; теперь его вычисляют оба
 * устройства из своих ключей, а сервер не участвует и участвовать не может.
 *
 * AES-256-GCM со случайным 96-битным одноразовым числом впереди шифротекста. Чистая JVM
 * (`javax.crypto`, minSdk 26) — один и тот же код на телефоне и на компьютере.
 */
object RelayCrypto {
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    /** `nonce ‖ AES-GCM(key, plaintext)`. */
    fun seal(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        return nonce + gcm(Cipher.ENCRYPT_MODE, key, nonce).doFinal(plaintext)
    }

    /** Обратное к [seal]; бросает, если кадр испорчен или ключ чужой (метка целостности GCM). */
    fun open(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size > NONCE_BYTES) { "blob too short" }
        val nonce = blob.copyOfRange(0, NONCE_BYTES)
        val body = blob.copyOfRange(NONCE_BYTES, blob.size)
        return gcm(Cipher.DECRYPT_MODE, key, nonce).doFinal(body)
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        }
}

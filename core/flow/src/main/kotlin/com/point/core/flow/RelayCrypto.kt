package com.point.core.flow

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object RelayCrypto {
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    fun seal(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        return nonce + gcm(Cipher.ENCRYPT_MODE, key, nonce).doFinal(plaintext)
    }

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

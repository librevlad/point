package com.point.core.flow

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end crypto for the relay (#161 v2). The relay server is a **blind pipe**: it stores blobs
 * by an opaque [mailboxId] and never holds the pairing token, so only the paired phone and PC can
 * read what crosses it. AES-256-GCM with a random 96-bit nonce prefixing the ciphertext; the key
 * and the mailbox id are both derived from the high-entropy pairing token by SHA-256.
 *
 * Pure JVM (`javax.crypto` + `java.util.Base64`, minSdk 26) — the same object runs unchanged on
 * Android and the Compose Desktop side.
 */
object RelayCrypto {
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    /** The unguessable routing key for one direction ("to-pc" / "to-phone") — what the relay stores
     *  by. Derived from the token, so possession of the id implies possession of the token. */
    fun mailboxId(token: String, direction: String): String =
        base64Url(sha256(token.toByteArray(Charsets.UTF_8) + "mbx:$direction".toByteArray(Charsets.UTF_8)))

    /** `nonce ‖ AES-GCM(key = H(token), plaintext)`. */
    fun seal(token: String, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        return nonce + gcm(Cipher.ENCRYPT_MODE, token, nonce).doFinal(plaintext)
    }

    /** Inverse of [seal]; throws if the blob was tampered with or the token is wrong (GCM auth tag). */
    fun open(token: String, blob: ByteArray): ByteArray {
        require(blob.size > NONCE_BYTES) { "blob too short" }
        val nonce = blob.copyOfRange(0, NONCE_BYTES)
        val body = blob.copyOfRange(NONCE_BYTES, blob.size)
        return gcm(Cipher.DECRYPT_MODE, token, nonce).doFinal(body)
    }

    private fun gcm(mode: Int, token: String, nonce: ByteArray): Cipher {
        val key = SecretKeySpec(
            sha256(token.toByteArray(Charsets.UTF_8) + "key".toByteArray(Charsets.UTF_8)), "AES",
        )
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

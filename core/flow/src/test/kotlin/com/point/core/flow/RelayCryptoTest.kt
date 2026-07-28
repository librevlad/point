package com.point.core.flow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay is a blind pipe (#161 v2): only holders of the pairing token can read a blob — the
 * server sees nothing but ciphertext and an opaque mailbox id. Pure JVM, so it runs unchanged on
 * the phone and the desktop.
 */
class RelayCryptoTest {

    private val token = "6ac2020a3f9b41d28e77c0165 b0eff6".filterNot { it == ' ' }
    private val other = "deadbeefcafebabe0011223344556677"

    private inline fun failsToOpen(block: () -> Unit): Boolean =
        try {
            block()
            false
        } catch (e: Exception) {
            true
        }

    @Test
    fun `seal then open with the same token returns the plaintext`() {
        val plain = "Чек · 693,40 ₴".toByteArray(Charsets.UTF_8)
        val blob = RelayCrypto.seal(token, plain)
        assertArrayEquals(plain, RelayCrypto.open(token, blob))
    }

    @Test
    fun `a different token cannot open the blob`() {
        val blob = RelayCrypto.seal(token, "secret".toByteArray())
        assertTrue(failsToOpen { RelayCrypto.open(other, blob) })
    }

    @Test
    fun `a tampered blob fails authentication`() {
        val blob = RelayCrypto.seal(token, "secret".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()
        assertTrue(failsToOpen { RelayCrypto.open(token, blob) })
    }

    @Test
    fun `mailboxId is deterministic and distinguishes direction and token`() {
        assertEquals(RelayCrypto.mailboxId(token, "to-pc"), RelayCrypto.mailboxId(token, "to-pc"))
        assertNotEquals(RelayCrypto.mailboxId(token, "to-pc"), RelayCrypto.mailboxId(token, "to-phone"))
        assertNotEquals(RelayCrypto.mailboxId(token, "to-pc"), RelayCrypto.mailboxId(other, "to-pc"))
    }

    @Test
    fun `mailboxId is an opaque, fixed-length base64url token`() {
        val id = RelayCrypto.mailboxId(token, "to-pc")
        assertEquals(43, id.length) // SHA-256 → 43 base64url chars, no padding
        assertFalse(id.contains(token))
        assertFalse(id.contains("=") || id.contains("/") || id.contains("+"))
    }
}

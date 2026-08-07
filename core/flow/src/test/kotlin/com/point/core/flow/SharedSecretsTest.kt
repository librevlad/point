package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedSecretsTest {

    @Test fun `непустое побеждает пустое, и неважно, чьё свежее`() {
        val phone = SharedSecrets(aiKey = "sk-phone", at = 100)
        val pc = SharedSecrets(at = 500)

        assertEquals("sk-phone", phone.mergedWith(pc).aiKey)
        assertEquals("sk-phone", pc.mergedWith(phone).aiKey)
    }

    @Test fun `при двух разных ключах выигрывает тот, что вписан позже`() {
        val old = SharedSecrets(aiKey = "sk-старый", at = 100)
        val new = SharedSecrets(aiKey = "sk-новый", at = 200)

        assertEquals("sk-новый", old.mergedWith(new).aiKey)
        assertEquals("sk-новый", new.mergedWith(old).aiKey)
    }

    @Test fun `ключи разных сервисов сливаются по отдельности`() {

        val phone = SharedSecrets(aiKey = "sk-ai", at = 100)
        val pc = SharedSecrets(speechKey = "gsk-speech", at = 900)

        val merged = phone.mergedWith(pc)

        assertEquals("sk-ai", merged.aiKey)
        assertEquals("gsk-speech", merged.speechKey)
    }

    @Test fun `одинаковый ключ не считается расхождением`() {
        val a = SharedSecrets(aiKey = "sk-один", at = 100)
        val b = SharedSecrets(aiKey = "sk-один", at = 900)

        assertEquals("sk-один", a.mergedWith(b).aiKey)
    }

    @Test fun `письмо разбирается обратно в те же ключи`() {
        val secrets = SharedSecrets(aiKey = "sk-ai", speechKey = "gsk", ocrKey = "ocr", at = 12345)

        assertEquals(secrets, SharedSecrets.decode(secrets.encode()))
    }

    @Test fun `пустых ключей в письме нет вовсе`() {

        val encoded = SharedSecrets(speechKey = "gsk", at = 5).encode()

        assertTrue("пустой ключ уехал в письме: $encoded", !encoded.contains(SharedSecrets.AI))
        assertEquals("gsk", SharedSecrets.decode(encoded).speechKey)
    }

    @Test fun `мусор вместо письма не роняет и не выдумывает ключей`() {
        val secrets = SharedSecrets.decode("это не письмо, а мусор")

        assertTrue(secrets.isEmpty)
    }
}

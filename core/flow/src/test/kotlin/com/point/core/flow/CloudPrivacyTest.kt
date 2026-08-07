package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPrivacyTest {

    private val europeanButLearns = ReaderPrivacy("Mistral, Франция (ЕС)", ReaderPromise.TRAINS)

    private val overseasButPromises = ReaderPrivacy("Groq, США", ReaderPromise.NO_TRAINING)

    private val silent = ReaderPrivacy("OCR.space, Германия (ЕС)", ReaderPromise.UNKNOWN)

    @Test
    fun `умолчание — максимум бесплатного`() {
        assertEquals(PrivacyLevel.FREE_FIRST, PrivacyLevel.DEFAULT)
        assertEquals(PrivacyLevel.DEFAULT, PrivacyLevel.of(null))
        assertEquals(PrivacyLevel.DEFAULT, PrivacyLevel.of("уровень-которого-нет"))
    }

    @Test
    fun `по умолчанию читают все, включая тех, кто учится на присланном`() {
        assertTrue(allowedAt(PrivacyLevel.FREE_FIRST, europeanButLearns))
        assertTrue(allowedAt(PrivacyLevel.FREE_FIRST, overseasButPromises))
        assertTrue(allowedAt(PrivacyLevel.FREE_FIRST, silent))
    }

    @Test
    fun `строгий уровень судит по обещанию, а не по стране`() {

        assertTrue(allowedAt(PrivacyLevel.NO_TRAINING, overseasButPromises))
        assertFalse(allowedAt(PrivacyLevel.NO_TRAINING, europeanButLearns))
    }

    @Test
    fun `молчание — не обещание`() {

        assertFalse(allowedAt(PrivacyLevel.NO_TRAINING, silent))
    }

    @Test
    fun `общая цепочка моделей на строгом уровне молчит — за неё обещать некому`() {
        assertFalse(allowedAt(PrivacyLevel.NO_TRAINING, AI_CHAIN_PRIVACY))
        assertTrue(allowedAt(PrivacyLevel.FREE_FIRST, AI_CHAIN_PRIVACY))
    }

    @Test
    fun `лестница настоящая — строгий уровень нигде не пропускает больше свободного`() {

        val all = ReaderPromise.entries.map { ReaderPrivacy("кто-то", it) } + AI_CHAIN_PRIVACY
        all.forEach { privacy ->
            if (allowedAt(PrivacyLevel.DEVICE_ONLY, privacy)) {
                assertTrue("$privacy — строгий пропустил там, где средний нет", allowedAt(PrivacyLevel.NO_TRAINING, privacy))
            }
            if (allowedAt(PrivacyLevel.NO_TRAINING, privacy)) {
                assertTrue("$privacy — средний пропустил там, где свободный нет", allowedAt(PrivacyLevel.FREE_FIRST, privacy))
            }
        }
    }

    @Test
    fun `прежний выбор «Только Европа» не снимается молча`() {

        assertEquals(PrivacyLevel.NO_TRAINING, PrivacyLevel.of("EUROPE_ONLY"))
    }

    @Test
    fun `только на телефоне — наружу не выпускается никто`() {
        assertFalse(allowedAt(PrivacyLevel.DEVICE_ONLY, europeanButLearns))
        assertFalse(allowedAt(PrivacyLevel.DEVICE_ONLY, overseasButPromises))
        assertFalse(allowedAt(PrivacyLevel.DEVICE_ONLY, AI_CHAIN_PRIVACY))
    }

    @Test
    fun `отбор не пересобирает очередь — порядок это ранжирование по замеру`() {
        val chain = listOf(
            "mistral-ocr" to europeanButLearns,
            "ocr-space" to silent,
            "ovh" to overseasButPromises,
        )
        assertEquals(
            listOf("mistral-ocr", "ocr-space", "ovh"),
            allowedBy(PrivacyLevel.FREE_FIRST, chain) { it.second }.map { it.first },
        )
        assertEquals(
            listOf("ovh"),
            allowedBy(PrivacyLevel.NO_TRAINING, chain) { it.second }.map { it.first },
        )
        assertTrue(allowedBy(PrivacyLevel.DEVICE_ONLY, chain) { it.second }.isEmpty())
    }

    @Test
    fun `у каждого уровня человек видит и выигрыш, и цену`() {
        PrivacyLevel.entries.forEach { level ->
            assertTrue("$level без названия", level.title.isNotBlank())
            assertTrue("$level без объяснения", level.what.length > 30)

            assertFalse("$level говорит на языке кода: ${level.what}", level.what.contains("провайдер"))
            assertFalse("$level говорит на языке кода: ${level.title}", level.title.contains("провайдер"))
        }
    }

    @Test
    fun `каждое обещание сказано словами человека`() {
        ReaderPromise.entries.forEach { promise ->
            assertTrue("$promise без слов для человека", promise.what.length > 20)
        }

        assertFalse(ReaderPromise.UNKNOWN.what.contains("обещал не"))
    }

    @Test
    fun `настройка не отменяет согласия и говорит об этом`() {
        assertTrue(PRIVACY_SETTING_HINT.contains("тапа"))
        assertTrue(PRIVACY_SETTING_HINT.contains("куда"))
        assertTrue(PRIVACY_SETTING_TITLE.isNotBlank())
    }
}

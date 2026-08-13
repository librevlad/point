package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.CloudPrivacySettings
import com.point.core.flow.PrivacyLevel
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Подпись говорит про режим (#943).
 *
 * Человек переключил приватность на «только на этом устройстве» и нажал чтение снимка. Утечки
 * не было — цепочка наружу не пошла. Но до тапа действие обещало ему «снимок уйдёт в сервис»,
 * то есть ровно то, что режим и запретил. Point сделал меньше обещанного и не сказал об этом.
 *
 * Решение владельца 13.08.2026: «Подпись говорит про режим». Дверь остаётся видной и
 * нажимаемой — меняются слова, а не список.
 */
class ModeSaysItClosedTheWayOutTest {

    private class Mode(private val level: PrivacyLevel) : CloudPrivacySettings {
        override fun level() = level
        override suspend fun setLevel(level: PrivacyLevel) = Unit
    }

    private class Outside(private val name: String) : Capability {
        override val id = CapabilityId(name)
        override val icon = ""
        override val meta = CapabilityMeta(network = true)
        override fun label(state: ObjectState) = name
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = state
    }

    private class Home(private val name: String) : Capability {
        override val id = CapabilityId(name)
        override val icon = ""
        override fun label(state: ObjectState) = name
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = state
    }

    private val outside = Outside("cloud-ocr")
    private val home = Home("copy")

    private fun bubbles(level: PrivacyLevel) = DefaultCapabilityRegistry(
        capabilities = setOf(outside, home),
        policy = DefaultBubblePolicy(),
        privacy = Mode(level),
    ).bubblesFor(ObjectState(ObjectKind.IMAGE))

    @Test fun `режим закрыт — действие называет причиной режим, а не обещает сервис`() {
        val cloud = bubbles(PrivacyLevel.DEVICE_ONLY).single { it.capabilityId == outside.id }

        assertNotNull("режим промолчал", cloud.unusableReason)
        assertEquals(com.point.core.flow.chainClosedBy(PrivacyLevel.DEVICE_ONLY), cloud.unusableReason)
    }

    @Test fun `дверь остаётся на месте — действие не пропало из списка`() {
        val ids = bubbles(PrivacyLevel.DEVICE_ONLY).map { it.capabilityId }

        assertEquals(listOf(outside.id, home.id).toSet(), ids.toSet())
    }

    @Test fun `домашнее действие режим не трогает`() {
        val local = bubbles(PrivacyLevel.DEVICE_ONLY).single { it.capabilityId == home.id }

        assertNull(local.unusableReason)
    }

    @Test fun `режим открыт — причины нет`() {
        val cloud = bubbles(PrivacyLevel.FREE_FIRST).single { it.capabilityId == outside.id }

        assertNull(cloud.unusableReason)
    }
}

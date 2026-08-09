package com.point.desktop

import com.point.core.flow.CloudScope
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.flow.Resolver
import com.point.core.model.ActionResult
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Аудит 2026-08-09, блок 1.2 — самое тяжёлое: клик по облачному действию сразу слал
 * файл наружу. Инвариант 9: объект покидает устройства только после явного «да»,
 * вопрос — в момент выбора, отказ не наказывает (P11).
 */
class CloudConsentTest {

    @get:Rule val temp = TemporaryFolder()

    private val ran = AtomicInteger(0)

    private inner class CloudRealizer : Realizer {
        override val capabilityId = CapabilityId("pc-understand")
        override val meta = RealizerMeta(kind = RealizerKind.CLOUD)
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            ran.incrementAndGet()
            return ActionResult.Done("Понято")
        }
    }

    private fun state(consentFile: File) = DesktopState(
        DesktopRegistry(emptySet()),
        DesktopResolver(setOf(CloudRealizer())),
        clipboard = { },
        consent = FileConsent(consentFile),
    )

    private var made = 0

    private fun item(): InboxItem {
        val file = temp.newFile("чек-${made++}.txt").apply { writeText("текст") }
        return InboxItem(
            PointObject("o$made", "text/plain", ScratchRef(file.absolutePath), ObjectState(ObjectKind.TEXT)),
        )
    }

    private fun bubble() = Bubble("ai", "Понять", CapabilityId("pc-understand"), ObjectState(ObjectKind.TEXT))

    private fun await(until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && !until()) Thread.sleep(20)
    }

    @Test
    fun `без согласия облачный клик не выполняется — задаётся вопрос словами последствий`() {
        val st = state(File(temp.root, "consent"))

        st.onBubble(item(), bubble())
        await { st.cloudAsk.value != null }

        val ask = st.cloudAsk.value
        assertNotNull("вопрос согласия обязан появиться", ask)
        assertEquals("Отправить в облако?", ask!!.title)
        assertTrue("последствия названы словами", ask.destination.isNotBlank())
        assertEquals("файл не должен уйти до «да»", 0, ran.get())
    }

    @Test
    fun `после «да» действие выполняется, и согласие запоминается`() {
        val consentFile = File(temp.root, "consent2")
        val st = state(consentFile)

        st.onBubble(item(), bubble())
        await { st.cloudAsk.value != null }
        st.approveCloud()
        await { ran.get() == 1 }

        assertEquals(1, ran.get())
        assertNull(st.cloudAsk.value)

        // Второй раз тот же вопрос не задаётся — согласие дано заранее (Конституция §11).
        st.onBubble(item(), bubble())
        await { ran.get() == 2 }
        assertEquals(2, ran.get())
        assertNull(st.cloudAsk.value)
    }

    @Test
    fun `отказ не наказывает — ничего не ушло, действие остаётся доступным`() {
        val st = state(File(temp.root, "consent3"))

        st.onBubble(item(), bubble())
        await { st.cloudAsk.value != null }
        st.declineCloud()

        assertEquals(0, ran.get())
        assertNull(st.cloudAsk.value)
        assertTrue(st.message.value.orEmpty().contains("остался на компьютере"))

        // Действие не исчезло: следующий клик снова задаёт вопрос.
        st.onBubble(item(), bubble())
        await { st.cloudAsk.value != null }
        assertNotNull(st.cloudAsk.value)
    }
}

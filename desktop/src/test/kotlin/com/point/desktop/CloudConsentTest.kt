package com.point.desktop

import com.point.core.flow.CloudScope
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.flow.Resolver
import com.point.core.flow.chainClosedBy
import com.point.core.model.ActionResult
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
 *
 * Дверей на этом пути две, и обе проверяются здесь (#1247, #1269). Первая — выбранный
 * человеком режим: согласие, данное заранее, конституция (§11) разрешает, а вот
 * подразумевать его нельзя, и «Только на этом устройстве» закрывает дорогу наружу до
 * всякого вопроса. Вторая — само «да». Проверяются они в одной воронке `perform`, а не
 * только на пути клика: иначе «да», сказанное до переключения режима, проносило объект мимо.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudConsentTest {

    @get:Rule val temp = TemporaryFolder()

    /**
     * Клик уходит в работу окна, и вопрос согласия появляется оттуда же. Планировщик
     * теста доводит эту работу до конца: «вопрос задан» и «файл не ушёл» — события,
     * а не то, что успело или не успело случиться за отведённые секунды.
     */
    private val dispatcher = StandardTestDispatcher()

    private val ran = AtomicInteger(0)

    private inner class CloudRealizer : Realizer {
        override val capabilityId = CapabilityId("pc-understand")
        override val meta = RealizerMeta(kind = RealizerKind.CLOUD)
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            ran.incrementAndGet()
            return ActionResult.Done("Понято")
        }
    }

    private fun state(
        consentFile: File,

        /** Режим спрашивается на каждом действии — как и в работе: переключили, подействовало. */
        level: () -> PrivacyLevel = { PrivacyLevel.DEFAULT },
    ) = DesktopState(
        DesktopRegistry(emptySet()),
        DesktopResolver(setOf(CloudRealizer())),
        clipboard = { },
        consent = FileConsent(consentFile),
        privacyLevel = level,
        background = dispatcher,
        io = dispatcher,
    )

    private var made = 0

    private fun item(): InboxItem {
        val file = temp.newFile("чек-${made++}.txt").apply { writeText("текст") }
        return InboxItem(
            PointObject("o$made", "text/plain", ScratchRef(file.absolutePath), ObjectState(ObjectKind.TEXT)),
        )
    }

    private fun bubble() = Bubble("ai", "Понять", CapabilityId("pc-understand"), ObjectState(ObjectKind.TEXT))

    @Test
    fun `без согласия облачный клик не выполняется — задаётся вопрос словами последствий`() = runTest(dispatcher) {
        val st = state(File(temp.root, "consent"))

        st.onBubble(item(), bubble())
        advanceUntilIdle()

        val ask = st.cloudAsk.value
        assertNotNull("вопрос согласия обязан появиться", ask)
        assertEquals("Отправить в облако?", ask!!.title)
        assertTrue("последствия названы словами", ask.destination.isNotBlank())
        assertEquals("файл не должен уйти до «да»", 0, ran.get())
    }

    @Test
    fun `после «да» действие выполняется, и согласие запоминается`() = runTest(dispatcher) {
        val consentFile = File(temp.root, "consent2")
        val st = state(consentFile)

        st.onBubble(item(), bubble())
        advanceUntilIdle()
        st.approveCloud()
        advanceUntilIdle()

        assertEquals(1, ran.get())
        assertNull(st.cloudAsk.value)

        // Второй раз тот же вопрос не задаётся — согласие дано заранее (Конституция §11).
        st.onBubble(item(), bubble())
        advanceUntilIdle()
        assertEquals(2, ran.get())
        assertNull(st.cloudAsk.value)
    }

    @Test
    fun `отказ не наказывает — ничего не ушло, действие остаётся доступным`() = runTest(dispatcher) {
        val st = state(File(temp.root, "consent3"))

        st.onBubble(item(), bubble())
        advanceUntilIdle()
        st.declineCloud()

        assertEquals(0, ran.get())
        assertNull(st.cloudAsk.value)
        assertTrue(st.message.value.orEmpty().contains("остался на компьютере"))

        // Действие не исчезло: следующий клик снова задаёт вопрос.
        st.onBubble(item(), bubble())
        advanceUntilIdle()
        assertNotNull(st.cloudAsk.value)
    }

    /**
     * Режим «Только на этом устройстве» — ответ человека, данный заранее (#1247, #893).
     *
     * Спрашивать в нём «отправить?» поздно и нечестно: объект туда не поедет в любом случае.
     * Прежде это держалось на одном `if`, который ни один тест не исполнял, — сверялся лишь
     * порядок двух строк в исходнике.
     */
    @Test
    fun `в режиме «только на этом устройстве» облачный клик не спрашивает и не отправляет`() = runTest(dispatcher) {
        val st = state(File(temp.root, "consent-device-only")) { PrivacyLevel.DEVICE_ONLY }

        st.onBubble(item(), bubble())
        advanceUntilIdle()

        assertEquals("файл ушёл наружу вопреки выбранному режиму", 0, ran.get())
        assertNull("вопрос об отправке в закрытом режиме — обман", st.cloudAsk.value)
        assertEquals(chainClosedBy(PrivacyLevel.DEVICE_ONLY), st.message.value)
    }

    /** Согласие, данное когда-то, не сильнее режима, выбранного сейчас (#1247). */
    @Test
    fun `согласие уже лежит, а режим закрыт — облако всё равно не открывается`() = runTest(dispatcher) {
        val consentFile = File(temp.root, "consent-kept")
        val open = state(consentFile)
        open.onBubble(item(), bubble()); advanceUntilIdle()
        open.approveCloud(); advanceUntilIdle()
        assertEquals("согласие обязано было лечь на диск", 1, ran.get())

        val closed = state(consentFile) { PrivacyLevel.DEVICE_ONLY }
        closed.onBubble(item(), bubble())
        advanceUntilIdle()

        assertEquals("режим не удержал объект, за который уже сказано «да»", 1, ran.get())
        assertNull("согласие есть, а вопрос всё равно задан", closed.cloudAsk.value)
        assertEquals(chainClosedBy(PrivacyLevel.DEVICE_ONLY), closed.message.value)
    }

    /**
     * Воронка одна: режим спрашивается в самом `perform`, а не только на пути клика (#1269).
     *
     * Человек нажал при открытом режиме, задумался над вопросом и закрыл дорогу наружу в
     * настройках. Сказанное после этого «да» относилось к прошлому состоянию — и проносило
     * объект мимо только что выбранного режима.
     */
    @Test
    fun `режим, закрытый пока висел вопрос, останавливает и сказанное «да»`() = runTest(dispatcher) {
        var level = PrivacyLevel.DEFAULT
        val st = state(File(temp.root, "consent-switched")) { level }

        st.onBubble(item(), bubble()); advanceUntilIdle()
        assertNotNull("вопрос обязан был появиться", st.cloudAsk.value)

        level = PrivacyLevel.DEVICE_ONLY
        st.approveCloud(); advanceUntilIdle()

        assertEquals("объект уехал мимо только что выбранного режима", 0, ran.get())
        assertEquals(chainClosedBy(PrivacyLevel.DEVICE_ONLY), st.message.value)
    }
}

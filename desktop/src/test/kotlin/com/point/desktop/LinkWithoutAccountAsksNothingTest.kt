package com.point.desktop

import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.flow.capabilities.DropLinkCapability
import com.point.core.flow.capabilities.NEEDS_ACCOUNT_FOR_LINK
import com.point.core.model.ActionResult
import com.point.core.model.Bubble
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Компьютер без аккаунта Point (#1022, строка матрицы DSK-012): человек соглашался выложить
 * файл по ссылке, а получал три догадки — вход, интернет и предел в 50 МБ. Ссылку выдаёт
 * сервер Point, и без аккаунта её выдавать некому: причина называется по тапу, согласия
 * никто не спрашивает, файл никуда не идёт.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkWithoutAccountAsksNothingTest {

    @get:Rule val temp = TemporaryFolder()

    /**
     * Ответ на тап рождается в работе окна: и названная причина, и вопрос согласия.
     * Планировщик теста доводит её до конца, поэтому «согласия не спрашивали» —
     * проверенный факт, а не срок, который истёк раньше вопроса.
     */
    private val dispatcher = StandardTestDispatcher()

    private val ran = AtomicInteger(0)

    private inner class DropRealizer : Realizer {
        override val capabilityId = DropLinkCapability.ID
        override val meta = RealizerMeta(kind = RealizerKind.CLOUD)
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            ran.incrementAndGet()
            return ActionResult.Done("Ссылка в буфере")
        }
    }

    private fun state(signedIn: Boolean) = DesktopState(
        DesktopRegistry(setOf(DropLinkCapability { signedIn })),
        DesktopResolver(setOf(DropRealizer())),
        clipboard = { },
        consent = FileConsent(File(temp.root, "consent-$signedIn")),
        background = dispatcher,
        io = dispatcher,
    )

    private var made = 0

    private fun item(): InboxItem {
        val file = temp.newFile("снимок-${made++}.jpg").apply { writeText("байты") }
        return InboxItem(
            PointObject("o$made", "image/jpeg", ScratchRef(file.absolutePath), ObjectState(ObjectKind.IMAGE)),
        )
    }

    private fun bubble() =
        Bubble("link", "Дать ссылку", DropLinkCapability.ID, ObjectState(ObjectKind.IMAGE))

    @Test
    fun `без аккаунта тап отвечает причиной, а не вопросом согласия`() = runTest(dispatcher) {
        val st = state(signedIn = false)

        st.onBubble(item(), bubble())
        advanceUntilIdle()

        assertEquals(NEEDS_ACCOUNT_FOR_LINK, st.message.value)
        assertNull("согласие спрошено зря", st.cloudAsk.value)
        assertEquals("файл ушёл, хотя выдать ссылку некому", 0, ran.get())
    }

    @Test
    fun `с аккаунтом всё идёт прежним ходом — согласие спрашивается`() = runTest(dispatcher) {
        val st = state(signedIn = true)

        st.onBubble(item(), bubble())
        advanceUntilIdle()

        assertNotNull("вопрос согласия пропал вместе с починкой", st.cloudAsk.value)
        assertEquals("файл не должен уйти до «да»", 0, ran.get())
    }
}

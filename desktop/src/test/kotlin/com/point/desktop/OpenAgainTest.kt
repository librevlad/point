package com.point.desktop

import com.point.core.flow.Resolver
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Живой прогон 2026-08-09: клик по «Было раньше» молчал — живой объект ленты не
 * выбирался, переоткрытый не становился выбранным. Клик по истории всегда отвечает:
 * объектом (живым или переоткрытым) или честным «файла больше нет».
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenAgainTest {

    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    private fun state(inbox: Inbox, journalStore: JournalStore? = null) = DesktopState(
        DesktopRegistry(emptySet()),
        object : Resolver {
            override fun realizerFor(capabilityId: CapabilityId) = error("не нужен")
        },
        clipboard = { },
        journalStore = journalStore,
        reopenPath = { path -> File(path).takeIf(File::isFile)?.let { inbox.addFile(it.absolutePath) } },
        background = dispatcher,
        io = dispatcher,
    )

    private fun entryFor(file: File, meta: Map<String, String> = emptyMap()) = JournalEntry(
        path = file.absolutePath,
        name = file.name,
        kind = ObjectKind.TEXT.name,
        mime = "text/plain",
        source = ObjectSource.PHONE_RELAY,
        at = 1L,
        meta = meta,
    )

    @Test
    fun `живой объект ленты возвращается выбором, а не молчанием`() = runTest(dispatcher) {
        val inbox = Inbox(temp.newFolder("in"))
        val st = state(inbox)
        val file = temp.newFile("живой.txt").apply { writeText("текст") }
        val live = InboxItem(
            PointObject("live", "text/plain", ScratchRef(file.absolutePath), ObjectState(ObjectKind.TEXT)),
        )
        st.onReceived(live)

        var opened: InboxItem? = null
        st.openAgain(entryFor(file)) { opened = it }

        assertSame(live, opened)
    }

    @Test
    fun `не в ленте, но файл жив — переоткрывается и возвращается первым`() = runTest(dispatcher) {
        val inbox = Inbox(temp.newFolder("in2"))
        val st = state(inbox)
        val file = temp.newFile("переоткрыть.txt").apply { writeText("текст") }

        var opened: InboxItem? = null
        st.openAgain(entryFor(file)) { opened = it }
        advanceUntilIdle()

        assertEquals(file.absolutePath, opened!!.obj.uri.value)
        assertSame(opened, st.items.value.first())
    }

    @Test
    fun `после рестарта переоткрытый объект несёт знание из журнала`() = runTest(dispatcher) {
        // Живой прогон 2026-08-09: после перезапуска ПК объект открывался голым —
        // приехавшее с телефона знание (entity.*) жило только в памяти процесса (PC2/PC5).
        val store = FileJournalStore(File(temp.root, "journal"))
        val file = temp.newFile("квитанция.jpg").apply { writeText("картинка") }

        val before = state(Inbox(temp.newFolder("in4")), store)
        before.onReceived(
            InboxItem(
                PointObject(
                    "arrived", "image/jpeg", ScratchRef(file.absolutePath),
                    ObjectState(ObjectKind.IMAGE),
                    metadata = mapOf("name" to "Квитанция", "entity.phone" to "+380222222222"),
                ),
            ),
            ObjectSource.PHONE_RELAY,
        )

        val after = state(Inbox(temp.newFolder("in5")), store)
        val entry = after.journal.value.single()
        var reopened: InboxItem? = null
        after.openAgain(entry) { reopened = it }
        advanceUntilIdle()

        assertEquals("+380222222222", reopened!!.obj.metadata["entity.phone"])
        assertEquals("Квитанция", reopened!!.obj.metadata["name"])
    }

    /**
     * Обещание «повторное нажатие исчезло» держалось только до перезапуска ПК (#995).
     *
     * Ссылка на прочитанное ложится в журнал метаданными и переживает рестарт, а признак
     * `HAS_TEXT` — свойство состояния и не переживает. `accepts` у «Извлечь текст» смотрит
     * на признак — и у прочитанного документа дверь рисовалась снова: та самая жалоба
     * DSK-040, ради которой признак и завели. Признак возвращает та же улика, что и хранится.
     */
    @Test
    fun `прочитанный документ остаётся прочитанным и после перезапуска компьютера`() = runTest(dispatcher) {
        val folder = temp.newFolder("книги")
        val doc = File(folder, "смета.xlsx").apply { writeText("книга") }
        val text = keepTextBesideDocument(doc, "Смета\tИтого")!!
        val st = state(Inbox(temp.newFolder("in6")))

        var reopened: InboxItem? = null
        st.openAgain(
            entryFor(doc, mapOf(com.point.core.flow.META_OCR_TEXT_REF to text.absolutePath)),
        ) { reopened = it }
        advanceUntilIdle()

        assertTrue(
            "у прочитанного документа снова нарисовали бы «Извлечь текст»",
            reopened!!.obj.state.has(Feature.HAS_TEXT),
        )
        assertEquals(
            text.absolutePath,
            reopened!!.obj.metadata[com.point.core.flow.META_OCR_TEXT_REF],
        )
    }

    /**
     * Улику убрали из своей папки — знания больше нет (#995).
     *
     * Текст ложится рядом с документом, в папке самого человека: он вправе его удалить или
     * перенести. Признак «текст есть» при этом оставался, дверь «Извлечь текст» не рисовалась,
     * а показывать было нечего — знание объекта заявлено, а за ним пусто. Вопрос снова «не
     * исследован», а не «исследован, не найдено» (Конституция).
     */
    @Test
    fun `текст убрали из папки — документ снова можно прочитать`() = runTest(dispatcher) {
        val folder = temp.newFolder("папка")
        val doc = File(folder, "договор.pdf").apply { writeText("документ") }
        val text = keepTextBesideDocument(doc, "текст договора")!!
        assertTrue("подготовить случай не вышло", text.delete())
        val st = state(Inbox(temp.newFolder("in7")))

        var reopened: InboxItem? = null
        st.openAgain(
            entryFor(doc, mapOf(com.point.core.flow.META_OCR_TEXT_REF to text.absolutePath)),
        ) { reopened = it }
        advanceUntilIdle()

        assertFalse(
            "документ остался прочитанным навсегда и без текста",
            reopened!!.obj.state.has(Feature.HAS_TEXT),
        )
        assertNull(
            "мёртвая ссылка осталась притворяться знанием",
            reopened!!.obj.metadata[com.point.core.flow.META_OCR_TEXT_REF],
        )
    }

    @Test
    fun `файла больше нет — null и честное сообщение`() = runTest(dispatcher) {
        val inbox = Inbox(temp.newFolder("in3"))
        val st = state(inbox)
        val gone = File(temp.root, "нет-такого.txt")

        var opened: InboxItem? = null
        st.openAgain(entryFor(gone)) { opened = it }
        advanceUntilIdle()

        assertNull(opened)
        assertEquals("Файла больше нет: нет-такого.txt", st.message.value)
    }
}

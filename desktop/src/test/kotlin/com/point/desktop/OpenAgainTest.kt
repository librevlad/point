package com.point.desktop

import com.point.core.flow.Resolver
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Живой прогон 2026-08-09: клик по «Было раньше» молчал — живой объект ленты не
 * выбирался, переоткрытый не становился выбранным. Клик по истории всегда отвечает:
 * объектом (живым или переоткрытым) или честным «файла больше нет».
 */
class OpenAgainTest {

    @get:Rule val temp = TemporaryFolder()

    private fun state(inbox: Inbox, journalStore: JournalStore? = null) = DesktopState(
        DesktopRegistry(emptySet()),
        object : Resolver {
            override fun realizerFor(capabilityId: CapabilityId) = error("не нужен")
        },
        clipboard = { },
        journalStore = journalStore,
        reopenPath = { path -> File(path).takeIf(File::isFile)?.let { inbox.addFile(it.absolutePath) } },
    )

    private fun entryFor(file: File) = JournalEntry(
        path = file.absolutePath,
        name = file.name,
        kind = ObjectKind.TEXT.name,
        mime = "text/plain",
        source = ObjectSource.PHONE_RELAY,
        at = 1L,
    )

    @Test
    fun `живой объект ленты возвращается выбором, а не молчанием`() {
        val inbox = Inbox(temp.newFolder("in"))
        val st = state(inbox)
        val file = temp.newFile("живой.txt").apply { writeText("текст") }
        val live = InboxItem(
            PointObject("live", "text/plain", ScratchRef(file.absolutePath), ObjectState(ObjectKind.TEXT)),
        )
        st.onReceived(live)

        assertSame(live, st.openAgain(entryFor(file)))
    }

    @Test
    fun `не в ленте, но файл жив — переоткрывается и возвращается первым`() {
        val inbox = Inbox(temp.newFolder("in2"))
        val st = state(inbox)
        val file = temp.newFile("переоткрыть.txt").apply { writeText("текст") }

        val opened = st.openAgain(entryFor(file))

        assertEquals(file.absolutePath, opened!!.obj.uri.value)
        assertSame(opened, st.items.value.first())
    }

    @Test
    fun `после рестарта переоткрытый объект несёт знание из журнала`() {
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
        val reopened = after.openAgain(entry)!!

        assertEquals("+380222222222", reopened.obj.metadata["entity.phone"])
        assertEquals("Квитанция", reopened.obj.metadata["name"])
    }

    @Test
    fun `файла больше нет — null и честное сообщение`() {
        val inbox = Inbox(temp.newFolder("in3"))
        val st = state(inbox)
        val gone = File(temp.root, "нет-такого.txt")

        assertNull(st.openAgain(entryFor(gone)))
        assertEquals("Файла больше нет: нет-такого.txt", st.message.value)
    }
}

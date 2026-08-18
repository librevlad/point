package com.point.desktop

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Буфер обмена — вещь человека, а память Point убирается по слову (#1093, #1081).
 *
 * Обе проверки про одно: компьютер делает наружу только то, о чём его попросили, и говорит,
 * что сделал. Сторожится обещание, а не формулировка.
 */
class ForgetOnPcTest {

    @get:Rule val temp = TemporaryFolder()

    private val mine = "то, что человек скопировал себе"
    private val arrived = "текст с телефона"

    private fun state(clipboard: TextClipboard) = DesktopState(
        DesktopRegistry(emptySet()),
        DesktopResolver(emptySet()),
        clipboard = clipboard,
    )

    private fun text(name: String, body: String): InboxItem {
        val file = temp.newFile(name).apply { writeText(body) }
        return InboxItem(
            PointObject(
                name,
                "text/plain",
                ScratchRef(file.absolutePath),
                ObjectState(ObjectKind.TEXT),
                metadata = mapOf("name" to name),
            ),
        )
    }

    @Test fun `текст с телефона не переписывает буфер обмена компьютера`() {
        var clip: String? = mine
        val st = state { clip = it }

        st.onReceived(text("прибыло.txt", arrived), ObjectSource.PHONE_RELAY)

        assertEquals("буфер человека подменён без просьбы", mine, clip)
        assertEquals("текст не готов к копированию по просьбе", arrived, st.clipboardText.value)
    }

    @Test fun `по просьбе текст в буфер кладётся`() {
        var clip: String? = null
        val st = state { clip = it }
        st.onReceived(text("прибыло.txt", arrived), ObjectSource.PHONE_RELAY)

        st.copyClipboardAgain()

        assertEquals(arrived, clip)
    }

    @Test fun `убрать всё — это убрать всё и сказать сколько`() {
        val st = state { }
        st.onReceived(text("первый.txt", "раз"), ObjectSource.PHONE_RELAY)
        st.onReceived(text("второй.txt", "два"), ObjectSource.LOCAL)

        val forgotten = st.forgetEverything { 2048L }

        assertEquals("убрано не всё, что помнилось", 2, forgotten)
        assertTrue("объекты остались на экране", st.items.value.isEmpty())
        assertTrue("журнал остался", st.journal.value.isEmpty())
        assertNull("текст остался наготове после уборки", st.clipboardText.value)
        assertTrue("об уборке не сказано ни слова", st.message.value.orEmpty().isNotBlank())
    }

    @Test fun `убирать нечего — тоже исход, а не молчание`() {
        val idle = com.point.desktop.ui.sweptText(null)
        val nothing = com.point.desktop.ui.sweptText(0)
        val some = com.point.desktop.ui.sweptText(3)

        assertNotEquals("после уборки экран не изменился ничем", idle, nothing)
        assertNotEquals("сделанное не отличается от несделанного", idle, some)
        assertTrue("сколько убрано — не сказано", some.contains("3"))
    }
}

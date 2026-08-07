package com.point.executors

import com.point.core.flow.CircleClipboard
import com.point.core.flow.Clipboard
import com.point.core.flow.LinkedPc
import com.point.core.flow.PcLinks
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValuesTravelTest {

    private class FakeLinks(private val pc: LinkedPc? = LinkedPc("d", "ПК", "k")) : PcLinks {
        override fun current() = pc
        override suspend fun save(pc: LinkedPc) = Unit
        override suspend fun clear() = Unit
    }

    private class Remembers : Clipboard {
        var last: String? = null
            private set
        override suspend fun copy(text: String, label: String) { last = text }
    }

    private class CircleRemembers : CircleClipboard {
        var last: String? = null
            private set
        override suspend fun offer(text: String) { last = text }
    }

    private fun card() = PointObject(
        id = "card",
        mime = "text/plain",
        uri = ScratchRef("4441 1144 5555 6666"),
        state = ObjectState(com.point.core.flow.KIND_IDENTIFIER),
    )

    @Test fun `значение без файла можно отправить на компьютер`() {

        val pc = PcCapability(FakeLinks())

        assertTrue("значение без файла снова нельзя отправить", pc.accepts(card().state))
    }

    @Test fun `набор по-прежнему не отправляется`() {

        val pc = PcCapability(FakeLinks())

        assertFalse(pc.accepts(ObjectState(ObjectKind.COLLECTION)))
    }

    @Test fun `«Скопировать» кладёт значение и в буфер круга`() = runTest {
        val here = Remembers()
        val circle = CircleRemembers()

        val result = CopyRealizer(here, circle).perform(card(), null)

        assertTrue(result is ActionResult.Done)
        assertEquals("4441 1144 5555 6666", here.last)
        assertEquals("на компьютере значения не будет", "4441 1144 5555 6666", circle.last)
    }

    @Test fun `номер карты уезжает без пробелов — его вставят в поле оплаты`() = runTest {
        val here = Remembers()
        val circle = CircleRemembers()

        CopyCardRealizer(
            com.point.core.flow.RegexEntityExtractor(),
            here,
            circle,
        ).perform(

            PointObject(
                id = "t",
                mime = "text/plain",
                uri = ScratchRef(
                    java.io.File.createTempFile("point-", ".txt")
                        .apply { writeText("оплата на 4242 4242 4242 4242 до пятницы"); deleteOnExit() }
                        .absolutePath,
                ),
                state = ObjectState(ObjectKind.TEXT),
            ),
            null,
        )

        assertEquals("4242424242424242", circle.last)
    }

    @Test fun `круга нет — «Скопировать» работает как работало`() = runTest {
        val here = Remembers()

        val result = CopyRealizer(here, CircleClipboard.None).perform(card(), null)

        assertTrue(result is ActionResult.Done)
        assertEquals("4441 1144 5555 6666", here.last)
    }
}

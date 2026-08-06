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

/**
 * Найденное значение доезжает до второго устройства и ложится в буфер (#611).
 *
 * Сценарии владельца: номер карты, найденный на телефоне, оказывается в буфере компьютера, потому
 * что платят с компьютера; номер квитанции со снимка экрана компьютера — в буфере телефона.
 *
 * Поправка владельца, с которой этот срез и стал маленьким: **«не понимаю что там путешествует,
 * номер карты это такой же текст как и ТТН»**. Он прав — путешествует текст, и ядро решило это
 * раньше нас (`ExtractedKinds`: вид называет вещь в мире, а не роль документа). Поэтому здесь нет
 * никакой машинерии для значений: снят один гейт и добавлен один тихий канал.
 */
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

    /** Номер карты, каким его рождает разбор: файла нет, значение лежит прямо в ссылке. */
    private fun card() = PointObject(
        id = "card",
        mime = "text/plain",
        uri = ScratchRef("4441 1144 5555 6666"),
        state = ObjectState(com.point.core.flow.KIND_IDENTIFIER),
    )

    @Test fun `значение без файла можно отправить на компьютер`() {
        // Прежде гейт `isFileBacked` запрещал именно это — то есть ровно то, ради чего человек
        // чаще всего и тянется ко второму устройству.
        val pc = PcCapability(FakeLinks())

        assertTrue("значение без файла снова нельзя отправить", pc.accepts(card().state))
    }

    @Test fun `набор по-прежнему не отправляется`() {
        // Сторож против чрезмерного снятия: набор — не один груз, у него нет ни файла, ни
        // значения, а есть список. Как его везти, ещё не решено, и делать вид, что решено, нельзя.
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
            // У обычного текста содержимое лежит в файле — так его и рождает приёмник.
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

        // Номер известный тестовый: разбор проверяет контрольную сумму, и выдуманный он
        // отвергает — это его работа, а не помеха тесту.
        assertEquals("4242424242424242", circle.last)
    }

    @Test fun `круга нет — «Скопировать» работает как работало`() = runTest {
        val here = Remembers()

        val result = CopyRealizer(here, CircleClipboard.None).perform(card(), null)

        assertTrue(result is ActionResult.Done)
        assertEquals("4441 1144 5555 6666", here.last)
    }
}

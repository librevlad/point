package com.point.desktop

import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * «Напечатать на ПК» (#291): телефон печатать не умеет, компьютер умеет, а весь транспорт
 * между ними уже построен. Здесь проверяется пара «что» и «как» — AWT за швом, тест чистый.
 */
class PrintActionTest {

    private val document = PointObject(
        "id", "application/pdf", ScratchRef("/tmp/doc.pdf"), ObjectState(ObjectKind.PDF),
    )

    private fun printer(name: String? = "HP LaserJet", onPrint: (File) -> Unit = {}) =
        object : Printer {
            override fun name() = name
            override fun print(file: File) = onPrint(file)
        }

    @Test
    fun `печатается именно тот файл, который дали`() = runTest {
        var printed: File? = null

        val result = PcPrintRealizer(printer(onPrint = { printed = it })).perform(document, null)

        assertEquals(File("/tmp/doc.pdf"), printed)
        assertTrue(result is ActionResult.Done)
    }

    /** Вопрос владельца «а на каком принтере печать?» — ответ обязан быть в самом сообщении:
     *  печать уходит на принтер по умолчанию, и человек имеет право видеть, куда. */
    @Test
    fun `сообщение называет принтер, на который ушла печать`() = runTest {
        val result = PcPrintRealizer(printer(name = "HP LaserJet")).perform(document, null) as ActionResult.Done

        assertTrue(result.message.contains("HP LaserJet"))
    }

    @Test
    fun `говорим, что произошло, а не обещаем готовую бумагу`() = runTest {
        // Прежде здесь стояло «В очереди «HP» · проверьте принтер», и довод был верный: включён ли
        // принтер и есть ли бумага — компьютеру не видно, отчитываться за чужую машину нельзя.
        //
        // Но человек читал это как «может быть»: состояние очереди — не ответ на «что случилось».
        // Довод сохранён, слова поменялись: задание ушло — это факт, и он назван; «напечатано» так
        // и не обещается (#596).
        val result = PcPrintRealizer(printer()).perform(document, null) as ActionResult.Done

        assertTrue("не сказано, что произошло: " + result.message, result.message.contains("Отправлено на печать"))
        assertTrue("не назван принтер", result.message.contains("HP"))
        assertTrue("обещали готовую бумагу", !result.message.contains("Напечатано"))
    }

    @Test
    fun `принтер сменился между тапом и печатью — отказ, а не печать в никуда`() = runTest {
        // Кнопка появилась, когда принтер был; к моменту работы его убрали — проверяем в
        // момент печати, потому что состояние чужой машины живёт своей жизнью.
        var printed = false
        val vanished = object : Printer {
            override fun name(): String? = null
            override fun print(file: File) { printed = true }
        }

        val result = PcPrintRealizer(vanished).perform(document, null)

        assertTrue(result is ActionResult.Failure)
        assertTrue("повторить можно — это не приговор", (result as ActionResult.Failure).recoverable)
        assertTrue(!printed)
    }

    @Test
    fun `принтера нет — честный отказ, а не тихая отправка в никуда`() = runTest {
        var printed = false

        val result = PcPrintRealizer(printer(name = null) { printed = true }).perform(document, null)

        assertTrue(result is ActionResult.Failure)
        assertTrue("печатать было некуда", !printed)
    }

    @Test
    fun `сбой принтера — понятный отказ, а не падение`() = runTest {
        val result = PcPrintRealizer(printer { error("принтер недоступен") }).perform(document, null)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }

    @Test
    fun `действие принимает любой объект — печатают что угодно`() {
        val cap = PcPrintCapability()

        assertTrue(cap.accepts(ObjectState(ObjectKind.PDF)))
        assertTrue(cap.accepts(ObjectState(ObjectKind.IMAGE)))
        assertEquals("Напечатать", cap.label(ObjectState(ObjectKind.TEXT)))
    }
}

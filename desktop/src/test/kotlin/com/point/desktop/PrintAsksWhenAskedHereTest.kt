package com.point.desktop

import com.point.core.flow.RequestOrigin
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Печать спрашивает того, кто рядом, и не спрашивает того, кого нет (#591).
 *
 * Владелец: «нет выбора принтера». Молча уходило на принтер по умолчанию — и это было решено
 * намеренно (#291): «диалог на компьютере тот, кто тапнул на телефоне, не увидит».
 *
 * Довод верный, но он про один из двух случаев. Тап **здесь** — человек сидит перед экраном, и ему
 * нужен не только принтер: формат, двусторонняя, диапазон страниц. Просьба **с телефона** — за
 * компьютером никого нет, диалог повиснет, задание не уйдёт вовсе.
 */
class PrintAsksWhenAskedHereTest {

    @get:Rule val temp = TemporaryFolder()

    private class Spy(private val agrees: Boolean = true) : Printer {
        var asked = false
            private set
        var printedSilently = false
            private set

        override fun name() = "HP LaserJet"
        override fun print(file: File) { printedSilently = true }
        override fun printAsking(file: File): Boolean {
            asked = true
            return agrees
        }
    }

    private fun document() = PointObject(
        id = "d",
        mime = "application/pdf",
        uri = ScratchRef(temp.newFile("акт.pdf").apply { writeText("документ") }.absolutePath),
        state = ObjectState(ObjectKind.PDF),
    )

    @Test fun `тап на компьютере открывает диалог`() = runTest {
        val printer = Spy()

        withContext(RequestOrigin(here = true)) {
            PcPrintRealizer(printer).perform(document(), null)
        }

        assertTrue("человека перед экраном не спросили о принтере", printer.asked)
        assertTrue("напечатали молча, отняв выбор", !printer.printedSilently)
    }

    @Test fun `просьба с телефона печатает молча — диалог там некому закрыть`() = runTest {
        val printer = Spy()

        withContext(RequestOrigin(here = false)) {
            PcPrintRealizer(printer).perform(document(), null)
        }

        assertTrue("диалог открыт там, где никого нет — задание не уйдёт", !printer.asked)
        assertTrue("задание не ушло", printer.printedSilently)
    }

    @Test fun `закрыл диалог — это «передумал», а не поломка`() = runTest {
        val printer = Spy(agrees = false)

        val result = withContext(RequestOrigin(here = true)) {
            PcPrintRealizer(printer).perform(document(), null)
        }

        assertTrue(result is ActionResult.Failure)
        assertEquals("Печать отменена — задание не ушло", (result as ActionResult.Failure).reason)
        assertTrue("после отмены всё равно напечатали", !printer.printedSilently)
    }
}

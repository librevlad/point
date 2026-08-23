package com.point.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Пункт «Отправить → Point» в Проводнике (#255, решение владельца 10.08.2026).
 *
 * Исходная формулировка — «выделил текст, правая кнопка, Point» — Windows не позволяет:
 * меню на выделенном тексте принадлежит браузеру или Word, а не системе. Вход заменён на
 * выполнимый и привычный: «Отправить», работающее и для нескольких файлов сразу. Для текста
 * на компьютере остаются прежние пути — кнопка «Буфер обмена» и перетаскивание в окно.
 */
class SendToPointTest {

    @get:Rule val temp = TemporaryFolder()

    private val calls = mutableListOf<List<String>>()

    private fun menu(folder: File?, answer: String = "") = ShortcutSendToMenu(folder) { command ->
        calls += command
        0 to answer
    }

    private fun installed() = File(temp.newFolder("Point"), "Point.exe").apply { writeText("") }

    @Test
    fun `ярлык ложится в папку «Отправить» этого человека`() {
        val appData = temp.newFolder("AppData")

        val folder = sendToFolder(appData.absolutePath)

        assertEquals(File(appData, "Microsoft/Windows/SendTo"), folder)
    }

    @Test
    fun `без домашней папки Windows ничего не выдумывается`() {
        assertNull(sendToFolder(null))
        assertNull(sendToFolder("  "))
    }

    @Test
    fun `ярлык указывает на установленный Point`() {
        val exe = installed()
        val link = File(temp.newFolder("SendTo"), "Point.lnk")

        val script = sendToScript(exe, link)

        assertTrue("ярлык кладётся не туда: $script", link.absolutePath in script)
        assertTrue("ярлык ведёт не в Point: $script", exe.absolutePath in script)
    }

    @Test
    fun `запись делается, когда её нет`() {
        val folder = File(temp.newFolder("home"), "SendTo")

        menu(folder).register(installed())

        assertTrue("папку «Отправить» не завели", folder.isDirectory)
        assertEquals(1, calls.size)
    }

    /** Переезд Point не оставляет ярлык, ведущий в никуда, — то же правило, что у правой кнопки. */
    @Test
    fun `переехавший Point переписывает свою запись`() {
        assertTrue(shellMenuNeedsUpdate("C:/Старое/Point.exe", "C:/Новое/Point.exe"))
        assertTrue("записи нет — надо записать", shellMenuNeedsUpdate(null, "C:/Point.exe"))
        assertFalse("переписали то, что и так верно", shellMenuNeedsUpdate("C:/Point.exe", "C:/Point.exe"))
    }

    @Test
    fun `ярлыка нет — и спрашивать не о чем`() {
        val folder = temp.newFolder("empty-sendto")

        assertNull(menu(folder, answer = "C:/Point.exe").target())
        assertTrue("зря будили Windows ради несуществующего ярлыка", calls.isEmpty())
    }

    @Test
    fun `существующий ярлык называет, куда ведёт`() {
        val folder = temp.newFolder("with-sendto")
        sendToShortcut(folder).writeText("")

        assertEquals("C:/Point.exe", menu(folder, answer = "C:/Point.exe\r\n").target())
    }

    @Test
    fun `выключение снимает запись и отвечает по эффекту`() {
        val folder = temp.newFolder("drop-sendto")
        sendToShortcut(folder).writeText("")

        assertTrue("ярлык снят, а выключение сочтено сбоем", menu(folder).unregister())

        assertFalse("запись осталась после выключения", sendToShortcut(folder).exists())
        assertTrue("снимать было нечего — это не сбой", menu(folder).unregister())
    }

    /** Успех записи — не код PowerShell, а ярлык, прочитанный обратно и ведущий в Point (#1082). */
    @Test
    fun `ярлык встал, только когда читается обратно и ведёт в Point`() {
        val exe = installed()
        val folder = File(temp.newFolder("home-ok"), "SendTo")
        var leadsTo = exe.absolutePath
        val windows = ShortcutSendToMenu(folder) { command ->
            val script = command.last()
            if ("CreateShortcut" in script && "Save()" in script) sendToShortcut(folder).writeText("")
            0 to leadsTo + "\r\n"
        }

        assertTrue("ярлык встал и ведёт в Point, а запись сочтена сбоем", windows.register(exe))

        // Windows ответила «готово», а ярлык ведёт не туда: это не успех.
        leadsTo = "C:/Старое/Point.exe"
        assertFalse("ярлык ведёт мимо Point, а запись сочтена успехом", windows.register(exe))

        // PowerShell отработал молча, а ярлыка на диске нет — тоже не успех.
        val silent = ShortcutSendToMenu(File(temp.newFolder("home-silent"), "SendTo")) { 0 to "" }
        assertFalse("ярлыка нет, а запись сочтена успехом", silent.register(exe))
    }

    @Test
    fun `не Windows — Point в системные меню не лезет`() {
        val nowhere = menu(folder = null)

        nowhere.register(installed())
        nowhere.unregister()

        assertNull(nowhere.target())
        assertTrue("на чужой системе Point всё-таки полез в меню", calls.isEmpty())
    }

    /** Приёмка: «Отправить» отдаёт сразу несколько файлов — Point обязан взять все. */
    @Test
    fun `несколько файлов сразу приходят все`() {
        val one = File(temp.newFolder("send"), "первый.txt").apply { writeText("a") }
        val two = File(one.parentFile, "второй.pdf").apply { writeText("b") }

        val taken = filesFromArgs(arrayOf(one.absolutePath, two.absolutePath, "C:/нет/такого.txt"))

        assertEquals(listOf(one, two), taken)
    }
}

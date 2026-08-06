package com.point.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * «Открыть в Point» по правой кнопке (#252).
 *
 * Карточка была закрыта преждевременно: приём файла из командной строки сделали, а системе про
 * пункт меню так и не сказали. Проверено на машине владельца 06.08.2026 — записи в реестре нет,
 * в папке «Отправить» Point тоже нет.
 *
 * Здесь судится то, что можно судить без реестра: какая команда должна быть записана и когда её
 * надо переписывать. Саму запись делает `reg.exe`, и подменять его в тесте значило бы проверять
 * свою подделку.
 */
class ShellMenuTest {

    @get:Rule val temp = TemporaryFolder()

    @Test fun `команда берёт в кавычки и путь установки, и путь файла`() {
        // Оба почти всегда с пробелами: «Program Files», «Мои документы». Без кавычек система
        // разрежет их по пробелу и передаст Point половину имени.
        val exe = temp.newFolder("Program Files", "Point").let { File(it, "Point.exe").apply { writeText("") } }

        val command = shellCommandFor(exe)

        assertTrue("путь установки без кавычек: $command", command.startsWith("\""))
        assertTrue("путь файла человека без кавычек: $command", command.endsWith("\"%1\""))
        assertTrue(command.contains(exe.absolutePath))
    }

    @Test fun `запись переписывается, когда Point переехал`() {
        // Молчаливая поломка: пункт меню остаётся, но ведёт в пустоту — система просто не находит
        // файл. Чинится само, без единого вопроса человеку.
        assertTrue(shellMenuNeedsUpdate("""\"C:\Старое\Point.exe\" \"%1\"""", """\"C:\Новое\Point.exe\" \"%1\""""))
        assertTrue("записи нет — надо записать", shellMenuNeedsUpdate(null, "любая"))
        assertFalse("переписали то, что и так верно", shellMenuNeedsUpdate("та же", "та же"))
    }

    @Test fun `из исходников не регистрируемся`() {
        // Путь ведёт в каталог сборки, который завтра исчезнет, и пункт меню останется указывать в
        // пустоту. Это ровно тот мусор, из-за которого программу запоминают плохо.
        assertNull(installedExecutable(null))
        assertNull(installedExecutable(""))
        assertNull(installedExecutable("/usr/bin/java"))
        assertNull("несуществующий файл принят за установку", installedExecutable("C:/нет/такого/Point.exe"))
    }

    @Test fun `установленную сборку узнаём`() {
        val exe = File(temp.newFolder("Point"), "Point.exe").apply { writeText("") }

        assertEquals(exe.absolutePath, installedExecutable(exe.absolutePath)?.absolutePath)
    }

    @Test fun `выключенная правая кнопка помнится между запусками`() {
        // Иначе обновление вернуло бы человеку пункт, который он снял, — и это читалось бы как
        // «Point не слушает».
        val home = temp.newFolder("point-home")
        val store = FilePcConfig(home)

        store.save(store.load().copy(rightClick = false))

        assertFalse("выключенное вернулось само", FilePcConfig(home).load().rightClick)
    }

    @Test fun `по умолчанию правая кнопка включена`() {
        assertTrue(FilePcConfig(temp.newFolder("point-home2")).load().rightClick)
    }
}

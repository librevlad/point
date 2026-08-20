package com.point.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShellMenuTest {

    @get:Rule val temp = TemporaryFolder()

    @Test fun `команда берёт в кавычки и путь установки, и путь файла`() {

        val exe = temp.newFolder("Program Files", "Point").let { File(it, "Point.exe").apply { writeText("") } }

        val command = shellCommandFor(exe)

        assertTrue("путь установки без кавычек: $command", command.startsWith("\""))
        assertTrue("путь файла человека без кавычек: $command", command.endsWith("\"%1\""))
        assertTrue(command.contains(exe.absolutePath))
    }

    @Test fun `запись переписывается, когда Point переехал`() {

        assertTrue(shellMenuNeedsUpdate("""\"C:\Старое\Point.exe\" \"%1\"""", """\"C:\Новое\Point.exe\" \"%1\""""))
        assertTrue("записи нет — надо записать", shellMenuNeedsUpdate(null, "любая"))
        assertFalse("переписали то, что и так верно", shellMenuNeedsUpdate("та же", "та же"))
    }

    @Test fun `из исходников не регистрируемся`() {

        assertNull(installedExecutable(null))
        assertNull(installedExecutable(""))
        assertNull(installedExecutable("/usr/bin/java"))
        assertNull("несуществующий файл принят за установку", installedExecutable("C:/нет/такого/Point.exe"))
    }

    @Test fun `установленную сборку узнаём`() {
        val exe = File(temp.newFolder("Point"), "Point.exe").apply { writeText("") }

        assertEquals(exe.absolutePath, installedExecutable(exe.absolutePath)?.absolutePath)
    }

    @Test fun `команда с пробелами и кавычками доезжает до реестрового файла без искажений`() {
        val command = "\"C:\\Point Files\\Point.exe\" \"%1\""

        val script = shellMenuRegFile(command, SHELL_MENU_TITLE)

        // В .reg-синтаксисе кавычка — `\"`, обратная косая — `\\`; иначе пункт остаётся без команды.
        val written = "@=\"\\\"C:\\\\Point Files\\\\Point.exe\\\" \\\"%1\\\"\""
        assertTrue("команда исказилась по дороге:\n$script", script.contains(written))
        assertTrue("название пункта потерялось", script.contains("@=\"$SHELL_MENU_TITLE\""))
        assertTrue(script.startsWith("Windows Registry Editor Version 5.00"))
        assertTrue(script.contains("[HKEY_CURRENT_USER\\Software\\Classes\\*\\shell\\Point]"))
        assertTrue(script.contains("[HKEY_CURRENT_USER\\Software\\Classes\\*\\shell\\Point\\command]"))
    }

    @Test fun `запись идёт файлом и успехом считается только команда, читаемая обратно`() {
        val command = "\"C:\\Program Files\\Point\\Point.exe\" \"%1\""
        var imported: ByteArray? = null
        val menu = RegistryShellMenu(
            run = { cmd ->
                when (cmd.getOrNull(1)) {
                    "import" -> {
                        imported = File(cmd[2]).readBytes()
                        0 to ""
                    }

                    // Реестр отвечает командой только после состоявшегося импорта.
                    "query" -> if (imported == null) 1 to "" else 0 to "    (По умолчанию)    REG_SZ    $command"
                    else -> 1 to ""
                }
            },
            windows = true,
        )

        assertTrue("успешная запись сочтена сбоем", menu.register(command, SHELL_MENU_TITLE))

        val bytes = imported ?: error("reg import не вызывался")
        assertTrue("файл без BOM UTF-16LE — русское название пункта побьётся", bytes.size > 2)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xFE, bytes[1].toInt() and 0xFF)
        val text = String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16LE)
        assertTrue("в файл уехало не то содержимое:\n$text", text == shellMenuRegFile(command, SHELL_MENU_TITLE))
    }

    @Test fun `сбой записи виден, а не глотается`() {
        val menu = RegistryShellMenu(
            run = { cmd -> if (cmd.getOrNull(1) == "import") 1 to "ERROR: Invalid syntax." else 1 to "" },
            windows = true,
        )

        assertFalse(
            "сбой регистрации сошёл за успех — переключатель снова соврёт «Показывается»",
            menu.register("\"C:\\Point\\Point.exe\" \"%1\"", SHELL_MENU_TITLE),
        )
    }

    @Test fun `пункт без команды успехом не считается`() {

        // Прежний дефект #1082 в лицах: заголовок записался, команда — нет, exit был проглочен.
        val menu = RegistryShellMenu(
            run = { cmd -> if (cmd.getOrNull(1) == "import") 0 to "" else 1 to "" },
            windows = true,
        )

        assertFalse("пункт-пустышка сочтён живым", menu.register("\"C:\\Point\\Point.exe\" \"%1\"", SHELL_MENU_TITLE))
    }

    @Test fun `вне Windows реестр не трогается`() {
        var touched = false
        val menu = RegistryShellMenu(run = { touched = true; 0 to "" }, windows = false)

        assertFalse(menu.register("\"C:\\Point\\Point.exe\" \"%1\"", SHELL_MENU_TITLE))
        menu.unregister()
        assertNull(menu.registeredCommand())
        assertFalse("вне Windows запущен reg", touched)
    }

    @Test fun `переключатель не прикрывает сбой словом об успехе`() {
        val ok = com.point.desktop.ui.rightClickLine(on = true, trouble = false)
        val broken = com.point.desktop.ui.rightClickLine(on = true, trouble = true)
        val off = com.point.desktop.ui.rightClickLine(on = false, trouble = false)

        assertTrue("сбой не назван сбоем", broken.contains("Не удалось"))
        assertFalse("сбой прикрыт словом об успехе", broken.contains(ok))
        assertTrue(ok.contains("Показывается"))
        assertTrue(off.contains("Не показывается"))
        assertFalse("выключенное выглядит сломанным", off.contains("Не удалось"))
    }

    @Test fun `выключенная правая кнопка помнится между запусками`() {

        val home = temp.newFolder("point-home")
        val store = FilePcConfig(home)

        store.save(store.load().copy(rightClick = false))

        assertFalse("выключенное вернулось само", FilePcConfig(home).load().rightClick)
    }

    @Test fun `по умолчанию правая кнопка включена`() {
        assertTrue(FilePcConfig(temp.newFolder("point-home2")).load().rightClick)
    }
}

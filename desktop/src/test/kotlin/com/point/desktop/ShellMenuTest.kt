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

    @Test fun `пункт без команды успехом не считается и в реестре не остаётся`() {

        // Прежний дефект #1082 в лицах: заголовок записался, команда — нет, exit был проглочен.
        val calls = mutableListOf<List<String>>()
        val menu = RegistryShellMenu(
            run = { cmd -> calls += cmd; if (cmd.getOrNull(1) == "import") 0 to "" else 1 to "" },
            windows = true,
        )

        assertFalse("пункт-пустышка сочтён живым", menu.register("\"C:\\Point\\Point.exe\" \"%1\"", SHELL_MENU_TITLE))

        // Ответ «не встало» без отката оставлял название без команды — тот самый мёртвый пункт.
        assertTrue("половина записи осталась в реестре: $calls", calls.any { it.getOrNull(1) == "delete" })
    }

    /**
     * Живой реестр на столько, на сколько его видит `RegistryShellMenu`: ключ пункта, подключ
     * команды и заведомо существующий родитель, по которому видно, что чтение вообще идёт.
     */
    private fun registry(
        menuKey: () -> Boolean,
        commandKey: () -> String?,
        readable: () -> Boolean = { true },
        onDelete: () -> Unit = {},
    ) = RegistryShellMenu(
        run = { cmd ->
            when {
                cmd.getOrNull(1) == "delete" -> { onDelete(); 0 to "" }
                !readable() -> -1 to ""
                cmd.getOrNull(2) == CLASSES_KEY -> 0 to CLASSES_KEY
                cmd.getOrNull(2) == COMMAND_KEY ->
                    commandKey()?.let { 0 to "    (По умолчанию)    REG_SZ    $it" } ?: (1 to "")

                cmd.getOrNull(2) == MENU_KEY -> if (menuKey()) 0 to MENU_KEY else 1 to ""
                else -> 1 to ""
            }
        },
        windows = true,
    )

    @Test fun `выключение отвечает по эффекту, а не кодом reg delete`() {
        val command = "\"C:\\Point\\Point.exe\" \"%1\""
        var inRegistry = true
        var deleteWorks = true
        val menu = registry(
            menuKey = { inRegistry },
            commandKey = { command.takeIf { inRegistry } },
            onDelete = { if (deleteWorks) inRegistry = false },
        )

        // Пункт пережил удаление — выключенный переключатель не скажет «Не показывается».
        deleteWorks = false
        assertFalse("пункт остался, а выключение сочтено успехом", menu.unregister())

        deleteWorks = true
        assertTrue("пункт снят, а выключение сочтено сбоем", menu.unregister())

        // Ключа не было и до того: `reg delete` ругается, но эффект — тот, что просили.
        assertTrue("снимать было нечего — это не сбой", menu.unregister())
    }

    @Test fun `снятость спрашивается про ключ пункта, а не про подключ команды`() {

        // Так выглядит мёртвый пункт, с которого началась карточка: строка в меню файла есть,
        // а команды у неё нет. Прежде выключение спрашивало только про команду — и отвечало
        // «снято» поверх оставшегося в Проводнике пункта.
        val menu = registry(menuKey = { true }, commandKey = { null })

        assertFalse("пункт остался в меню файла, а выключение сочтено успехом", menu.unregister())
        assertEquals("ключ пункта есть, а пункт сочтён снятым", true, menu.present())
    }

    @Test fun `сбой чтения реестра снятым пунктом не считается`() {

        // `reg` не запустился: про пункт не известно ничего. «Не прочиталось» — это не «снято»,
        // иначе переключатель уверенно говорит «Не показывается», ни разу не заглянув в реестр.
        val menu = registry(menuKey = { false }, commandKey = { null }, readable = { false })

        assertNull("нечитаемый реестр ответил, будто пункта в нём нет", menu.present())
        assertFalse("нечитаемый реестр сочтён пустым", menu.unregister())
    }

    @Test fun `вне Windows реестр не трогается`() {
        var touched = false
        val menu = RegistryShellMenu(run = { touched = true; 0 to "" }, windows = false)

        assertFalse(menu.register("\"C:\\Point\\Point.exe\" \"%1\"", SHELL_MENU_TITLE))
        assertTrue("вне Windows снимать нечего — это не сбой", menu.unregister())
        assertNull(menu.registeredCommand())
        assertEquals("вне Windows пункт меню файла сочтён стоящим", false, menu.present())
        assertFalse("вне Windows запущен reg", touched)
    }

    @Test fun `выключатель говорит, что стоит на деле, по реестру и ярлыку`() {
        val exe = File(temp.newFolder("Point"), "Point.exe").apply { writeText("") }
        val command = shellCommandFor(exe)

        fun holds(on: Boolean, menu: Boolean?, cmd: String?, link: Boolean, target: String?) =
            rightClickHolds(on, exe, menuPresent = menu, command = cmd, linkPresent = link, linkTarget = target)

        assertEquals(true, holds(on = true, menu = true, cmd = command, link = true, target = exe.absolutePath))
        assertEquals(
            "реестр пуст, а «включено» сочтено стоящим",
            false,
            holds(on = true, menu = false, cmd = null, link = true, target = exe.absolutePath),
        )
        assertEquals(
            "ярлыка нет, а «включено» сочтено стоящим",
            false,
            holds(on = true, menu = true, cmd = command, link = false, target = null),
        )
        assertEquals(
            "из исходников пункта нет — врать про него нечего",
            false,
            rightClickHolds(true, null, menuPresent = true, command = command, linkPresent = true, linkTarget = exe.absolutePath),
        )
        assertEquals(true, holds(on = false, menu = false, cmd = null, link = false, target = null))

        // Ключ пункта без команды — оставшийся в Проводнике пункт, а не снятый.
        assertEquals(
            "пункт остался в меню файла, а «выключено» сочтено снятым",
            false,
            holds(on = false, menu = true, cmd = null, link = false, target = null),
        )

        // Ярлык на диске лежит, а куда ведёт — Windows не ответила: это тоже не «снято».
        assertEquals(
            "ярлык остался, а «выключено» сочтено снятым",
            false,
            holds(on = false, menu = false, cmd = null, link = true, target = null),
        )

        // Реестр не прочитался — знания нет ни за, ни против: подписи вердикта не из чего взять.
        assertNull(
            "нечитаемый реестр сошёл за пустой",
            holds(on = false, menu = null, cmd = null, link = false, target = null),
        )
        assertNull(
            "нечитаемый реестр сошёл за пустой",
            holds(on = true, menu = null, cmd = command, link = true, target = exe.absolutePath),
        )
    }

    /**
     * Сторож обещания (#1082): переключатель не прикрывает сбой словом об успехе — и не выносит
     * вердикта, пока правда о пункте не прочитана. Такой тест не цементирует формулировку: он
     * охраняет отсутствие неправды и потому не удаляется.
     */
    @Test fun `переключатель не прикрывает сбой словом об успехе`() {
        val ok = com.point.desktop.ui.rightClickLine(on = true, trouble = false)
        val broken = com.point.desktop.ui.rightClickLine(on = true, trouble = true)
        val off = com.point.desktop.ui.rightClickLine(on = false, trouble = false)
        val unknown = com.point.desktop.ui.rightClickLine(on = true, trouble = null)

        assertTrue("сбой не назван сбоем", broken.contains("Не удалось"))
        assertFalse("сбой прикрыт словом об успехе", broken.contains(ok))
        assertTrue(ok.contains("Показывается"))
        assertTrue(off.contains("Не показывается"))
        assertFalse("выключенное выглядит сломанным", off.contains("Не удалось"))

        // Непрочитанное — не «сбоя нет» и не «сбой»: вердикта в подписи нет ни того, ни другого.
        assertFalse("непрочитанное выдано за успех", unknown.contains(ok))
        assertFalse("непрочитанное выдано за сбой", unknown.contains("Не удалось"))
        assertFalse("непрочитанное выдано за снятое", unknown.contains(off))
        assertEquals(
            "до чтения включённое и выключенное отвечают по-разному, хотя знания нет ни там, ни там",
            unknown,
            com.point.desktop.ui.rightClickLine(on = false, trouble = null),
        )
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

    private companion object {

        const val CLASSES_KEY = """HKCU\Software\Classes"""
        const val MENU_KEY = """HKCU\Software\Classes\*\shell\Point"""
        const val COMMAND_KEY = """HKCU\Software\Classes\*\shell\Point\command"""
    }
}

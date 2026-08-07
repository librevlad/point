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

package com.point.desktop

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Окно не стоит, пока рождается объект (#995).
 *
 * Рождение объекта читает файл: у PDF — весь, потому что признак «текст файлом не достаётся»
 * судит весь документ, как и исполнитель. Звали его прямо из обработчика броска, из входа в
 * ребёнка набора и из нажатия по строке «Недавнего» — тем самым потоком, который рисует окно.
 * Толстый PDF останавливал окно до конца чтения, и человек смотрел на неподвижный экран
 * (Конституция: первый экран без I/O).
 *
 * Работа окна идёт по планировщику теста: «объект родился» — состоявшееся событие, которого
 * дожидается `advanceUntilIdle`, а не истёкший срок в секундах.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WindowDoesNotWaitForTheFileTest {

    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    private fun windowWith(inbox: Inbox, read: MutableList<String>) = DesktopState(
        DesktopRegistry(emptySet()),
        DesktopResolver(emptySet()),
        clipboard = { },
        background = dispatcher,
        io = dispatcher,
        reopenPath = { path ->
            read += path
            File(path).takeIf(File::isFile)?.let { inbox.addFile(it.absolutePath) }
        },
    )

    @Test
    fun `вход в ребёнка набора отпускает окно раньше, чем файл прочитан`() = runTest(dispatcher) {
        val inbox = Inbox(temp.newFolder("inbox"))
        val child = temp.newFile("child.txt").apply { writeText("текст ребёнка") }
        val read = mutableListOf<String>()
        val window = windowWith(inbox, read)

        window.openPath(child.absolutePath)

        assertTrue("окно читало файл своим потоком — толстый PDF остановил бы его", read.isEmpty())
        assertTrue("объект родился до того, как окно отпустили", window.items.value.isEmpty())

        advanceUntilIdle()

        assertEquals(listOf(child.absolutePath), read)
        assertEquals(child.absolutePath, window.items.value.single().obj.uri.value)
    }

    @Test
    fun `брошенный файл тоже рождается не потоком окна`() = runTest(dispatcher) {
        val inbox = Inbox(temp.newFolder("dropped"))
        val brought = temp.newFile("brought.txt").apply { writeText("принесённое") }
        val window = windowWith(inbox, mutableListOf())

        window.receiveFiles(inbox, listOf(brought.absolutePath), ObjectSource.DROPPED)

        assertTrue("окно читало брошенный файл своим потоком", window.items.value.isEmpty())

        advanceUntilIdle()

        assertEquals(brought.absolutePath, window.items.value.single().obj.uri.value)
    }

    /**
     * Третья дверь рождения объекта — нажатие по строке «Недавнего», которой уже нет в ленте.
     *
     * Она звала переоткрытие прямо из обработчика нажатия: на этом пути окно стояло и раньше,
     * а с этой карточкой стало стоять дольше — признак «текст файлом не достаётся» судит весь
     * документ, и толстый PDF читается секундами.
     */
    @Test
    fun `нажатие по недавнему отпускает окно раньше, чем файл прочитан`() = runTest(dispatcher) {
        val inbox = Inbox(temp.newFolder("недавнее"))
        val kept = temp.newFile("забытый.txt").apply { writeText("вчерашнее") }
        val read = mutableListOf<String>()
        val window = windowWith(inbox, read)
        val entry = JournalEntry(
            path = kept.absolutePath,
            name = kept.name,
            kind = com.point.core.model.ObjectKind.TEXT.name,
            mime = "text/plain",
            source = ObjectSource.DROPPED,
            at = 1L,
        )

        var opened: InboxItem? = null
        window.openAgain(entry) { opened = it }

        assertTrue("окно читало файл своим потоком — толстый PDF остановил бы его", read.isEmpty())
        assertTrue("объект родился до того, как окно отпустили", window.items.value.isEmpty())

        advanceUntilIdle()

        assertEquals(listOf(kept.absolutePath), read)
        assertEquals(kept.absolutePath, opened!!.obj.uri.value)
        assertEquals(kept.absolutePath, window.items.value.single().obj.uri.value)
    }

    /** Пачка остаётся одним объектом-коллекцией (#1099) — шов приёма её не разбирает. */
    @Test
    fun `пачка брошенных файлов остаётся одной коллекцией`() = runTest(dispatcher) {
        val inbox = Inbox(temp.newFolder("пачка"))
        val a = temp.newFile("drop-a.txt").apply { writeText("а") }
        val b = temp.newFile("drop-b.txt").apply { writeText("б") }
        val window = windowWith(inbox, mutableListOf())

        window.receiveFiles(inbox, listOf(a.absolutePath, b.absolutePath), ObjectSource.DROPPED)
        advanceUntilIdle()

        val born = window.items.value.single()
        assertEquals(com.point.core.model.ObjectKind.COLLECTION, born.obj.state.kind)
        assertEquals(listOf(a.absolutePath, b.absolutePath), collectionChildren(born.obj))
    }
}

package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private data class Entry(val name: String, val file: Boolean = true)

private fun scan(entries: List<Entry>, limit: Int = 500, scanLimit: Int = 10_000) =
    collectionContent(
        entries = entries.asSequence(),
        limit = limit,
        scanLimit = scanLimit,
        isFile = { it.file },
        name = { it.name },
    )

class CollectionContentTest {

    @Test
    fun `небольшой набор показан целиком`() {
        val content = scan(listOf(Entry("b.txt"), Entry("a.txt")))

        assertEquals(listOf("a.txt", "b.txt"), content.shown.map { it.name })
        assertEquals(2, content.total)
        assertFalse(content.truncated)
        assertFalse(content.atLeast)
    }

    @Test
    fun `каталоги не элементы набора`() {
        val content = scan(listOf(Entry("папка", file = false), Entry("a.txt")))

        assertEquals(listOf("a.txt"), content.shown.map { it.name })
        assertEquals(1, content.total)
    }

    @Test
    fun `большой набор обрезан, но настоящее число известно`() {
        val content = scan((1..1000).map { Entry("f%04d.txt".format(it)) }, limit = 500)

        assertEquals(500, content.shown.size)
        assertEquals(1000, content.total)
        assertTrue(content.truncated)
        assertFalse(content.atLeast)
    }

    @Test
    fun `показаны первые по алфавиту, а не первые по обходу`() {
        val content = scan(listOf(Entry("я.txt"), Entry("Б.txt"), Entry("а.txt")), limit = 2)

        assertEquals(listOf("а.txt", "Б.txt"), content.shown.map { it.name })
        assertEquals(3, content.total)
    }

    @Test
    fun `обход упёрся в потолок — счёт объявлен нижней границей`() {
        val content = scan((1..100).map { Entry("f%03d.txt".format(it)) }, limit = 10, scanLimit = 20)

        assertEquals(10, content.shown.size)
        assertEquals(20, content.total)
        assertTrue(content.atLeast)
        assertTrue(content.truncated)
    }

    @Test
    fun `потолок обхода останавливает бесконечное дерево`() {
        var walked = 0
        val endless = generateSequence { walked++; Entry("f$walked.txt") }

        val content = collectionContent(
            entries = endless,
            limit = 5,
            scanLimit = 50,
            isFile = { true },
            name = { it.name },
        )

        assertEquals(5, content.shown.size)
        assertEquals(50, content.total)
        assertTrue(content.atLeast)

        assertTrue("обход не остановился на потолке - $walked", walked <= 51)
    }

    @Test
    fun `пустой набор — пустое содержимое`() {
        val content = scan(emptyList())

        assertEquals(0, content.total)
        assertFalse(content.truncated)
        assertEquals(CollectionContent.empty<Entry>(), content)
    }

    @Test
    fun `превращение элементов сохраняет счёт`() {
        val mapped = scan((1..10).map { Entry("f$it.txt") }, limit = 3).map { it.name }

        assertEquals(3, mapped.shown.size)
        assertEquals(10, mapped.total)
        assertTrue(mapped.truncated)
    }
}

package com.point.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RemovedUsageJournalTest {

    private val dir = Files.createTempDirectory("point-usage").toFile().apply { deleteOnExit() }

    @Test
    fun `журнал прежних версий стирается при обновлении`() {
        val journal = File(dir, "usage.jsonl").apply { writeText("""{"t":"SHARED","d":"IMAGE"}""" + "\n") }
        val consent = File(dir, "consent").apply { createNewFile() }

        RemovedUsageJournal(dir).erase()

        assertFalse("журнал остался лежать на устройстве", journal.exists())
        assertFalse("согласие на журнал осталось лежать на устройстве", consent.exists())
    }

    @Test
    fun `соседние файлы в той же папке не трогаются`() {
        val chosen = File(dir, "chosen-apps.json").apply { writeText("[]") }

        RemovedUsageJournal(dir).erase()

        assertTrue("уборка задела чужой файл", chosen.exists())
    }

    @Test
    fun `на чистой установке уборке нечего делать`() {
        val empty = Files.createTempDirectory("point-usage-empty").toFile().apply { deleteOnExit() }

        RemovedUsageJournal(empty).erase()

        assertTrue(empty.isDirectory)
    }
}

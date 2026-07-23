package com.point.data

import com.point.core.flow.UsageEvent
import com.point.core.flow.UsageEventType
import com.point.core.flow.UsageSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

/** Pure JVM — the journal writes to an injected dir; consent is a marker file. */
class FileUsageJournalTest {

    private val dir = Files.createTempDirectory("point-usage").toFile().apply { deleteOnExit() }
    private val journal = FileUsageJournal(dir)

    @Test
    fun `records nothing until the user opts in`() = runTest {
        assertFalse(journal.isEnabled())
        journal.record(UsageEvent(UsageEventType.SHARED))
        assertEquals(UsageSummary(0, 0, 0), journal.summary())
    }

    @Test
    fun `after opt-in it records and summarises events`() = runTest {
        journal.setEnabled(true)
        journal.record(UsageEvent(UsageEventType.SHARED, "IMAGE"))
        journal.record(UsageEvent(UsageEventType.ACTION, "ocr"))
        journal.record(UsageEvent(UsageEventType.ACTION, "pdf"))
        journal.record(UsageEvent(UsageEventType.COMPLETED, "save"))

        val s = journal.summary()
        assertEquals(1, s.objects)
        assertEquals(2, s.actions)
        assertEquals(1, s.completed)
        assertEquals(2.0, s.actionsPerObject, 0.001)
    }

    @Test
    fun `opting out wipes the journal`() = runTest {
        journal.setEnabled(true)
        journal.record(UsageEvent(UsageEventType.SHARED))
        journal.setEnabled(false)
        assertFalse(journal.isEnabled())

        journal.setEnabled(true) // re-enabled — the old data is gone
        assertEquals(UsageSummary(0, 0, 0), journal.summary())
    }
}

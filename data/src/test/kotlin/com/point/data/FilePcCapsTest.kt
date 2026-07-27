package com.point.data

import com.point.core.flow.PcRemoteAction
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The cached remote actions of the paired PC (#80) — warm at process start. */
class FilePcCapsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = FilePcCaps(File(tmp.root, "usage"))

    @Test
    fun `saved caps come back warm across instances`() = runTest {
        store().save(listOf(PcRemoteAction("pc-open", "Открыть на компьютере")))

        val fresh = store()
        assertEquals(listOf(PcRemoteAction("pc-open", "Открыть на компьютере")), fresh.all())
    }

    @Test
    fun `clear leaves nothing`() = runTest {
        val s = store()
        s.save(listOf(PcRemoteAction("pc-copy", "В буфер компьютера")))
        s.clear()
        assertTrue(s.all().isEmpty())
        assertTrue(store().all().isEmpty())
    }

    @Test
    fun `empty by default`() {
        assertTrue(store().all().isEmpty())
    }
}

package com.point.data

import com.point.core.model.FlowSnapshotFrame
import com.point.core.flow.FlowSnapshotStore
import com.point.core.flow.HistoryFootprint
import com.point.core.flow.HistoryStore
import com.point.core.model.HistoryEntry
import com.point.core.model.PointObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * «Забыть всё» забывает всё, что обещано (#1026).
 *
 * Проверяется не текст на экране, а эффект: после уборки на устройстве не остаётся ни копии
 * объекта, ни снимка разбора, ни переписки с моделью.
 */
class FilesPointMemoryTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "point-memory-" + System.nanoTime())
    private val historyDir = File(root, "history").apply { mkdirs() }
    private val scratchDir = File(root, "scratch").apply { mkdirs() }
    private val snapshotFile = File(root, "flow-snapshot.json")
    private val exchangesDir = File(root, "llm-log").apply { mkdirs() }

    private class FakeHistory(private val dir: File) : HistoryStore {
        override suspend fun record(obj: PointObject) = Unit
        override suspend fun update(obj: PointObject) = Unit
        override suspend fun recent(limit: Int): List<HistoryEntry> = emptyList()
        override suspend fun open(entryId: String): PointObject? = null
        override suspend fun remove(entryId: String) = Unit
        override suspend fun clearAll() { dir.deleteRecursively() }
        override suspend fun footprint() = HistoryFootprint(
            count = dir.listFiles()?.count { it.isFile } ?: 0,
            bytes = 0L,
        )
    }

    private class FakeSnapshot(private val file: File) : FlowSnapshotStore {
        override suspend fun save(frames: List<FlowSnapshotFrame>) = Unit
        override suspend fun load(): List<FlowSnapshotFrame> = emptyList()
        override suspend fun clear() { file.delete() }
    }

    private fun memory(objects: com.point.core.flow.ObjectStore = FakeScratch(scratchDir)) = FilesPointMemory(
        history = FakeHistory(historyDir),
        snapshot = FakeSnapshot(snapshotFile),
        objects = objects,
        historyDir = historyDir,
        scratchDir = scratchDir,
        snapshotFile = snapshotFile,
        exchangesDir = exchangesDir,
    )

    private fun fill() {
        File(historyDir, "a.jpg").writeText("копия объекта")
        File(scratchDir, "b.jpg").writeText("объект, с которым человек работает")
        snapshotFile.writeText("{\"frames\":[]}")
        File(exchangesDir, "llm-1.txt").writeText("переписка с моделью")
    }

    @Test fun `забыто всё, что Point помнит об объектах`() = runTest {
        fill()
        val gone = memory().forgetAll()

        assertEquals("перечень не посчитан", 1, gone.count)
        assertTrue("вес не посчитан до уборки", gone.bytes > 0)
        listOf(historyDir, scratchDir, snapshotFile, exchangesDir).forEach {
            assertFalse("осталось на устройстве- " + it.name, it.exists())
        }
    }

    @Test fun `вес считается по всему, а не по одному перечню`() = runTest {
        fill()
        val kept = memory().footprint()

        val onlyHistory = File(historyDir, "a.jpg").length()
        assertTrue("копия объекта и переписка не посчитаны", kept.bytes > onlyHistory)
    }

    @Test fun `пустая память не выдаёт себя за полную`() = runTest {
        assertEquals(HistoryFootprint(0, 0L), memory().footprint())
    }

    @Test fun `сорвавшаяся уборка не отменяет остальные`() = runTest {
        fill()
        val stubborn = object : FakeScratch(scratchDir) {
            override suspend fun clear() = error("не вышло")
        }
        memory(stubborn).forgetAll()

        assertFalse("перечень уцелел из-за чужого отказа", historyDir.exists())
        assertFalse("переписка уцелела из-за чужого отказа", exchangesDir.exists())
    }

    private open class FakeScratch(private val dir: File) : com.point.core.flow.ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("не нужно")
        override suspend fun ingestMultiple(sources: List<String>) = error("не нужно")
        override suspend fun put(
            result: com.point.core.model.ResultObject,
            from: PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("не нужно")
        override suspend fun children(
            collection: PointObject,
            limit: Int,
        ) = error("не нужно")
        override suspend fun readText(obj: PointObject, limit: Int) = ""
        override suspend fun newScratchFile(extension: String) = com.point.core.model.ScratchRef("")
        override suspend fun clear() { dir.deleteRecursively() }
    }
}

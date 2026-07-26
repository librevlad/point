package com.point.data

import com.point.core.model.FlowSnapshotFrame
import com.point.core.model.ObjectKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The crash-proof journey journal (#7): a tiny JSON file outside scratch, pure JVM. */
class FileFlowSnapshotStoreTest {

    private val file = File.createTempFile("flow-snap", ".json").apply { delete(); deleteOnExit() }
    private val store = FileFlowSnapshotStore(file)

    private val frames = listOf(
        FlowSnapshotFrame(
            id = "root", kind = ObjectKind.IMAGE, mime = "image/png", ref = "/scratch/shot.png",
            metadata = mapOf("name" to "shot.png", "entity.phone" to "+380671234567"),
        ),
        FlowSnapshotFrame(
            id = "step", kind = ObjectKind.TEXT, mime = "text/plain", ref = "/scratch/ocr.txt",
            metadata = emptyMap(), viaCapabilityId = "ocr", viaTitle = "Распознать текст",
        ),
    )

    @Test
    fun `save then load round-trips the journey`() = runTest {
        store.save(frames)
        val loaded = store.load()
        assertEquals(frames, loaded)
    }

    @Test
    fun `no snapshot - empty journey`() = runTest {
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `clear removes the snapshot`() = runTest {
        store.save(frames)
        store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `a corrupt snapshot is an empty journey, not a crash`() = runTest {
        file.writeText("{oops")
        assertTrue(store.load().isEmpty())
    }
}

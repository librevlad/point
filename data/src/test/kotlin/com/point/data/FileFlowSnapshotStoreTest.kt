package com.point.data

import com.point.core.model.FlowSnapshotFrame
import com.point.core.model.ObjectKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun `происхождение переживает журнал — src едет в метаданных, узел собирается заново`() = runTest {
        val read = listOf(
            FlowSnapshotFrame(
                id = "shot", kind = ObjectKind.IMAGE, mime = "image/png", ref = "/scratch/shot.png",
                metadata = mapOf(
                    "entity.address" to "Відділення №9, Київ",
                    "entity.address" + com.point.core.flow.META_SOURCE_SUFFIX to
                        com.point.core.model.Provenance.HUMAN.wire,
                ),
            ),
        )
        store.save(read)

        val restored = store.load().single()
        val node = MetadataEntityEnricher().enrich(
            com.point.core.model.PointObject(
                restored.id, restored.mime,
                com.point.core.model.ScratchRef(restored.ref),
                com.point.core.model.ObjectState(restored.kind),
                restored.metadata,
            ),
        ).objects.single()

        assertEquals(com.point.core.model.Provenance.HUMAN, node.provenance)
        assertEquals("подтверждено вами", com.point.core.flow.provenanceLabel(node.provenance))
    }

    @Test
    fun `легаси-журнал без src не врёт — узел молчит, а не выдумывает чтение`() = runTest {
        val old = listOf(
            FlowSnapshotFrame(
                id = "shot", kind = ObjectKind.IMAGE, mime = "image/png", ref = "/scratch/shot.png",
                metadata = mapOf("entity.address" to "Відділення №9, Київ"),
            ),
        )
        store.save(old)

        val restored = store.load().single()
        val node = MetadataEntityEnricher().enrich(
            com.point.core.model.PointObject(
                restored.id, restored.mime,
                com.point.core.model.ScratchRef(restored.ref),
                com.point.core.model.ObjectState(restored.kind),
                restored.metadata,
            ),
        ).objects.single()

        assertEquals(com.point.core.model.Provenance.GIVEN, node.provenance)
        assertNull(com.point.core.flow.provenanceLabel(node.provenance))
    }
}

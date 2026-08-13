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

    private fun foundNode(id: String, value: String, region: String? = null) =
        com.point.core.model.PointObject(
            id = id,
            mime = "text/plain",
            uri = com.point.core.model.ValueRef(value),
            state = com.point.core.model.ObjectState(com.point.core.flow.KIND_IDENTIFIER),
            metadata = buildMap {
                put(com.point.core.flow.META_ENTITY_TRACK, value)
                region?.let { put(com.point.core.flow.META_AT_REGION, it) }
            },
            provenance = com.point.core.model.Provenance.OCR,
            sourceObjects = listOf("root"),
            creatorAction = "identifier-enricher",
        )

    @Test
    fun `найденные объекты, связи и Focus переживают process death целиком`() = runTest {
        val a = foundNode("root:identifier:A", "20 4514 9154 9395", region = "10.0 20.0 210.0 60.0")
        val b = foundNode("root:identifier:B", "59 0012 3456 7890", region = "10.0 120.0 210.0 160.0")
        val journey = listOf(
            frames.first().copy(
                found = listOf(a, b),
                relations = listOf(
                    com.point.core.model.Relation(a.id, com.point.core.model.RelationType.FOUND_IN, "root"),
                    com.point.core.model.Relation(b.id, com.point.core.model.RelationType.FOUND_IN, "root"),
                ),
                focusRegion = "10.0 120.0 210.0 160.0",
                focusIds = "w3 w4",
            ),
        )
        store.save(journey)

        val restored = store.load().single()

        assertEquals(listOf(a, b), restored.found)
        assertEquals(2, restored.relations.size)
        assertEquals("10.0 120.0 210.0 160.0", restored.focusRegion)
        assertEquals("w3 w4", restored.focusIds)

        val (first, second) = restored.found
        assertTrue("два объекта одного kind обязаны остаться различимыми", first.id != second.id)
        assertTrue(
            "и различимыми по месту на источнике",
            first.metadata[com.point.core.flow.META_AT_REGION] != second.metadata[com.point.core.flow.META_AT_REGION],
        )
    }

    @Test
    fun `старый журнал без found и relations читается как раньше`() = runTest {
        file.writeText(
            """[{"id":"root","kind":"IMAGE","mime":"image/png","ref":"/scratch/shot.png",""" +
                """"metadata":{"name":"shot.png"},"via":null,"viaTitle":null}]""",
        )

        val restored = store.load().single()

        assertEquals("root", restored.id)
        assertEquals("shot.png", restored.metadata["name"])
        assertTrue(restored.found.isEmpty())
        assertTrue(restored.relations.isEmpty())
        assertNull(restored.focusRegion)
        assertNull(restored.focusIds)
    }

    @Test
    fun `file-backed находка с умершим payload не теряет знание`() = runTest {
        val gone = com.point.core.model.PointObject(
            id = "root:crop",
            mime = "image/jpeg",
            uri = com.point.core.model.ScratchRef("/scratch/dead-crop.jpg"),
            state = com.point.core.model.ObjectState(ObjectKind.IMAGE),
            metadata = mapOf("entity.plate" to "AA1234BB"),
        )
        store.save(listOf(frames.first().copy(found = listOf(gone))))

        val restored = store.load().single().found.single()

        assertEquals("AA1234BB", restored.metadata["entity.plate"])
        assertEquals("узел жив, даже когда файла больше нет", "root:crop", restored.id)
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
                    "entity.address" + com.point.core.flow.META_ALT_SUFFIX to "Відділення №8, Київ",
                ),
            ),
        )
        store.save(read)

        val restored = store.load().single()
        val node = MetadataEntityInvestigationRealizer().look(
            com.point.core.model.PointObject(
                restored.id, restored.mime,
                com.point.core.model.ScratchRef(restored.ref),
                com.point.core.model.ObjectState(restored.kind),
                restored.metadata,
            ),
        ).objects.single()

        assertEquals(com.point.core.model.Provenance.HUMAN, node.provenance)
        assertEquals("подтверждено вами", com.point.core.flow.provenanceLabel(node.provenance))

        assertEquals("человеческое слово осталось главным", "Відділення №9, Київ", node.uri.value)
        assertEquals(
            "машинная история пережила журнал",
            listOf("Відділення №8, Київ"),
            com.point.core.flow.alternativesOf(restored.metadata, "entity.address"),
        )
        assertTrue(
            "разрешённый человеком спор — не спор",
            !com.point.core.flow.isDisputed(restored.metadata, "entity.address"),
        )
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
        val node = MetadataEntityInvestigationRealizer().look(
            com.point.core.model.PointObject(
                restored.id, restored.mime,
                com.point.core.model.ScratchRef(restored.ref),
                com.point.core.model.ObjectState(restored.kind),
                restored.metadata,
            ),
        ).objects.single()

        assertEquals(com.point.core.model.Provenance.UNKNOWN, node.provenance)
        assertNull(com.point.core.flow.provenanceLabel(node.provenance))
    }
}

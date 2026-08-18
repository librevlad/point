package com.point.data

import com.point.core.model.FlowSnapshotFrame
import com.point.core.model.ObjectKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Разбор не теряется от того, что его сохранили дважды разом (#1116).
 *
 * Два сохранения, попавшие друг на друга, оставляли на диске склейку двух версий: валидную
 * запись и хвост прежней, более длинной. Такой файл не читается, и при следующем запуске
 * человек молча оставался без своего разбора.
 */
class SnapshotSurvivesConcurrentSaveTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "point-snapshot-" + System.nanoTime())
        .apply { mkdirs() }
    private val file = File(dir, "flow-snapshot.json")
    private val store = FileFlowSnapshotStore(file)

    private fun frames(count: Int, mark: String): List<FlowSnapshotFrame> = (1..count).map {
        FlowSnapshotFrame(
            id = "$mark-$it",
            kind = ObjectKind.IMAGE,
            mime = "image/png",
            ref = "/scratch/$mark-$it.png",
            metadata = mapOf("name" to "$mark номер $it", "ocr.text.ref" to "/scratch/$mark-$it.txt"),
        )
    }

    @Test fun `одновременные сохранения не оставляют склейки`() = runTest {
        val long = frames(40, "длинный")
        val short = frames(1, "короткий")
        store.save(long)

        repeat(20) {
            listOf(
                async { store.save(long) },
                async { store.save(short) },
                async { store.save(long) },
            ).awaitAll()

            val read = store.load()
            assertTrue(
                "снимок прочитан склейкой- кадров " + read.size,
                read.size == long.size || read.size == short.size,
            )
        }
    }

    @Test fun `после записи черновик не остаётся рядом`() = runTest {
        store.save(frames(3, "обычный"))

        val leftovers = dir.listFiles()?.filter { it.name.endsWith(".writing") }.orEmpty()
        assertTrue("черновик записи остался на диске", leftovers.isEmpty())
        assertEquals(3, store.load().size)
    }

    @Test fun `битый снимок читается как отсутствующий, а не роняет разбор`() = runTest {
        file.writeText("[{\"id\":\"a\",\"kind\":\"IMAGE\"} и хвост прежней версии")

        assertTrue("битый файл выдал кадры", store.load().isEmpty())
    }

    @Test fun `забыть всё убирает и снимок, и черновик`() = runTest {
        store.save(frames(2, "обычный"))
        File(dir, "flow-snapshot.json.writing").writeText("недописанное")

        store.clear()

        assertFalse("снимок остался", file.exists())
        assertFalse("черновик остался", File(dir, "flow-snapshot.json.writing").exists())
    }
}

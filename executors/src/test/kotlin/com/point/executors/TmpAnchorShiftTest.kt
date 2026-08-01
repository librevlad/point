package com.point.executors

import com.point.core.flow.Atom
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.flow.LlmClient
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.SpreadsheetWriter
import com.point.core.flow.reconcile
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

/** ВРЕМЕННЫЙ щуп ревью: спор модели с атомами при вставке строки в alignRows (#294 vs #258). */
class TmpAnchorShiftTest {

    private fun llm(answer: String) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            val f = File.createTempFile("point-ans", ".txt").apply { deleteOnExit(); writeText(answer) }
            return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(f.absolutePath))
        }
    }

    private var lastRows: List<List<String>>? = null
    private var lastCandidates: Map<Pair<Int, Int>, List<String>> = emptyMap()
    private val writer = object : SpreadsheetWriter {
        override suspend fun write(
            rows: List<List<String>>,
            candidates: Map<Pair<Int, Int>, List<String>>,
        ): ScratchRef {
            lastRows = rows
            lastCandidates = candidates
            return ScratchRef(File.createTempFile("point-xlsx", ".xlsx").apply { deleteOnExit() }.absolutePath)
        }
    }

    /** Накладная: три строки, дата в первом столбце повторяется. */
    private fun invoice(): PointObject {
        val layer = AtomLayer(
            listOf(
                Atom("d1", "16.07", Box(10f, 100f, 60f, 120f)),
                Atom("n1", "Гречка", Box(70f, 100f, 160f, 120f)),
                Atom("q1", "42", Box(170f, 100f, 200f, 120f)),
                Atom("d2", "16.07", Box(10f, 140f, 60f, 160f)),
                Atom("n2", "Овес", Box(70f, 140f, 160f, 160f)),
                Atom("q2", "31", Box(170f, 140f, 200f, 160f)),
                Atom("d3", "16.07", Box(10f, 180f, 60f, 200f)),
                Atom("n3", "Пшено", Box(70f, 180f, 160f, 200f)),
                Atom("q3", "53", Box(170f, 180f, 200f, 200f)),
            ),
        )
        val dump = File.createTempFile("point-atoms", ".tsv").apply {
            deleteOnExit(); writeText(AtomCodec.encode(layer))
        }
        return PointObject(
            "id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE),
            metadata = mapOf(META_OCR_ATOMS_REF to dump.absolutePath),
        )
    }

    @Test
    fun probe() = runTest {
        // Уровень 1: чистая арифметика координат.
        val a = listOf(listOf("16.07", "Гречка", "42"), listOf("16.07", "Пшено", "53"))
        val b = listOf(
            listOf("16.07", "Гречка", "42"),
            listOf("16.07", "Овес", "31"),
            listOf("16.07", "Пшено", "53"),
        )
        val consensus = reconcile(listOf(a, b))
        println("== consensus.rows ==")
        consensus.rows.forEachIndexed { i, r -> println("  $i: $r") }
        println("anchor((1,0), [16.07, 1б.07]) = " + anchorCandidates(1 to 0, listOf("16.07", "1б.07"), consensus.rows))
        println("anchor((1,2), [53, 5З])       = " + anchorCandidates(1 to 2, listOf("53", "5З"), consensus.rows))

        // Уровень 2: живой реализатор. Модель A пропустила среднюю строку и спорит с атомами
        // о дате и количестве строки «Пшено»; модель B прочитала все три строки.
        val modelA = """
            [[{"ids":["d1"]},{"ids":["n1"]},{"ids":["q1"]}],
             [{"ids":["d3"],"text":"1б.07"},{"ids":["n3"]},{"ids":["q3"],"text":"5З"}]]
        """.trimIndent()
        val modelB = """
            [[{"ids":["d1"]},{"ids":["n1"]},{"ids":["q1"]}],
             [{"ids":["d2"]},{"ids":["n2"]},{"ids":["q2"]}],
             [{"ids":["d3"]},{"ids":["n3"]},{"ids":["q3"]}]]
        """.trimIndent()
        val result = ExcelRealizer(listOf(llm(modelA), llm(modelB)), writer).perform(invoice())
        println("== result == $result")
        println("== xlsx rows ==")
        lastRows!!.forEachIndexed { i, r -> println("  $i: $r") }
        println("== dropdowns ==")
        lastCandidates.forEach { (k, v) -> println("  $k -> $v") }
    }
}

package com.point.executors

import com.point.core.flow.Atom
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.flow.LlmClient
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.SpreadsheetWriter
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

class TmpReviewProbeTest {

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

    private fun imageWithAtoms(): PointObject {
        val layer = AtomLayer(
            listOf(
                Atom("h1", "Трек-номер", Box(10f, 60f, 120f, 80f)),
                Atom("a1", "20", Box(10f, 100f, 40f, 120f)),
                Atom("a2", "4514 9154", Box(45f, 100f, 140f, 120f)),
                Atom("a3", "9395", Box(145f, 100f, 190f, 120f)),
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
    fun `probe - fully hallucinated addressed answer`() = runTest {
        val realizer = ExcelRealizer(
            listOf(llm("""[[{"ids":[1]},{"ids":[2]}],[{"ids":[3]},{"ids":[4]}]]""")),
            writer,
        )
        val result = realizer.perform(imageWithAtoms())
        println("PROBE result class = ${result::class.simpleName}")
        if (result is ActionResult.Success) {
            println("PROBE metadata = ${result.result.metadata}")
        }
        if (result is ActionResult.Failure) {
            println("PROBE failure = ${result.reason}")
        }
        println("PROBE lastRows = $lastRows")
        println("PROBE lastCandidates = $lastCandidates")
    }
}

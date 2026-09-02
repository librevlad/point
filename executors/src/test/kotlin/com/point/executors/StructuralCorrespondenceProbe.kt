package com.point.executors

import com.point.core.flow.UnderstandRealizer

import com.point.core.flow.META_ALT_SUFFIX
import com.point.core.flow.META_ANCHOR_COL_SUFFIX
import com.point.core.flow.META_ANCHOR_ROW_SUFFIX
import com.point.core.flow.META_CELL_ANCHOR_PREFIX
import com.point.core.flow.META_EVIDENCE_SUFFIX
import com.point.core.flow.anchoredCellKey
import com.point.core.flow.isAnnotationKey
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Вертикальная проба STRUCTURAL NODE CORRESPONDENCE (#1176): два независимых зрячих
 * наблюдения над одними физическими клетками кадра 04 обязаны сойтись в одни канонические
 * узлы по якорям — нумерация наблюдателя в идентичность не входит.
 *
 * Посев — восемь якорных ВОПРОСОВ (заглушка «—» + сомнение → слепой вопрос брифа;
 * «вопрос без наблюдения» как примитив — открытый хвост RFC §11, отчитывается отдельно).
 * Дальше — обычные витки «Понять»: ротация даёт наблюдателей A и B, соответствие сводит
 * их к одним узлам, большинство накопленных прочтений выбирает значение.
 *
 * Живая сеть; без POINT_CORR_PROBE пропускается. Запуск:
 *   POINT_CORR_PROBE=1 ./gradlew :executors:testDebugUnitTest --tests "*StructuralCorrespondenceProbe*" --rerun
 */
class StructuralCorrespondenceProbe {

    private val seeds = listOf(
        "Капуста білоголова свіжа" to "маса брутто",
        "Капуста білоголова свіжа" to "маса нетто",
        "Капуста білоголова свіжа" to "брутто однієї порції",
        "Капуста білоголова свіжа" to "нетто однієї порції",
        "Олія соняшникова рафінована" to "маса брутто",
        "Олія соняшникова рафінована" to "нетто однієї порції",
        "Цукор" to "маса брутто",
        "Цукор" to "нетто однієї порції",
    )

    /** Эталонные значения по якорю строки — множеством: имена колонок наблюдатели дают свои. */
    private val truth = mapOf(
        "Капуста білоголова свіжа" to setOf("11 625", "9 300", "116,25", "93"),
        "Олія соняшникова рафінована" to setOf("300", "3"),
        "Цукор" to setOf("500", "5"),
    )

    @Test
    fun `наблюдатели A и B сходятся в канонические узлы по якорям`() {
        assumeTrue(System.getenv("POINT_CORR_PROBE") != null)
        val report = StringBuilder()
        fun say(line: String) {
            println(line)
            report.append(line).append(System.lineSeparator())
            System.getenv("POINT_CORR_REPORT")?.let { runCatching { File(it).writeText(report.toString()) } }
        }
        runBlocking {
            val corpus = File(System.getenv("POINT_CORPUS") ?: "C:/Users/User/point-corpus")
            val realizer = UnderstandRealizer(EyesOnlyCorpusProbe.liveChain())

            // «Вопрос без наблюдения» (RFC §11): узел существует якорями, факта нет —
            // ни заглушки, ни мусора в споре.
            val seeded = buildMap {
                seeds.forEach { (row, col) ->
                    val key = anchoredCellKey(row, col)
                    put(key + META_ANCHOR_ROW_SUFFIX, row)
                    put(key + META_ANCHOR_COL_SUFFIX, col)
                }
            }
            var obj = PointObject(
                "04", "image/jpeg",
                ScratchRef(File(corpus, "04.jpg").absolutePath),
                ObjectState(ObjectKind.IMAGE),
                seeded,
            )
            fun nodes(meta: Map<String, String>) = com.point.core.flow.structuralNodes(meta).toSet()
            val seededKeys = nodes(obj.metadata)
            say("Посеяно якорных вопросов: ${seededKeys.size}")

            var rounds = 0
            var dry = 0
            while (rounds < 4 && dry < 2) {
                rounds++
                val before = nodes(obj.metadata)
                val outcome = runCatching { realizer.perform(obj, null) }.getOrNull()
                val found = (outcome as? ActionResult.Done)?.findings
                if (found == null) {
                    say("виток $rounds не ответил: " + ((outcome as? ActionResult.Failure)?.reason ?: "ошибка"))
                    dry++
                    continue
                }
                val beforeMeta = obj.metadata
                obj = obj.copy(
                    metadata = obj.metadata + found.metadata,
                    state = obj.state.copy(features = obj.state.features + found.features),
                )
                val after = nodes(obj.metadata)
                val landedInSeeded = seededKeys.count { obj.metadata[it] != beforeMeta[it] }
                say(
                    "виток $rounds · ${(outcome as ActionResult.Done).message} · " +
                        "узлов было ${before.size}, стало ${after.size} (новых ${(after - before).size}), " +
                        "легло в посевные: $landedInSeeded",
                )
                dry = if (rounds > 1 && com.point.core.flow.spiralDelta(beforeMeta, obj.metadata) == null) dry + 1 else 0
            }

            say("")
            val all = nodes(obj.metadata)
            var answered = 0
            var open = 0
            var truthHits = 0
            var truthMisses = 0
            all.sorted().forEach { key ->
                val row = obj.metadata[key + META_ANCHOR_ROW_SUFFIX].orEmpty()
                val col = obj.metadata[key + META_ANCHOR_COL_SUFFIX].orEmpty()
                val value = obj.metadata[key].orEmpty()
                val spor = if (obj.metadata[key + META_ALT_SUFFIX].isNullOrBlank()) "" else " · спор жив"
                val kind = when {
                    key in seededKeys && value.isNotBlank() -> { answered++; "ОТВЕЧЕН" }
                    key in seededKeys -> { open++; "открыт" }
                    else -> "новый"
                }
                val expected = truth.entries.firstOrNull { com.point.core.flow.sameFact("cell", it.key, row) }?.value
                val hit = expected != null && value.isNotBlank() &&
                    expected.any { com.point.core.flow.sameFact(key, it, value) }
                if (expected != null && value.isNotBlank()) { if (hit) truthHits++ else truthMisses++ }
                say("«$row» × «$col» = «$value» · $kind" + (if (hit) " · ЭТАЛОН" else "") + spor)
            }
            say("")
            say("Посевные: отвечено $answered из ${seededKeys.size}, открытых ${open}; всего узлов ${all.size}")
            say("Против эталонного множества строки: верно $truthHits, мимо $truthMisses")
        }
    }
}

package com.point.executors

import com.point.core.flow.UnderstandRealizer

import com.point.core.flow.AGREE_MARK
import com.point.core.flow.META_ACTOR_SUFFIX
import com.point.core.flow.META_ALT_SUFFIX
import com.point.core.flow.META_EVIDENCE_SUFFIX
import com.point.core.flow.META_SOURCE_SUFFIX
import com.point.core.flow.cellKey
import com.point.core.flow.sameFact
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Вертикальная проба адресуемой ячейки (#1176, эксперимент CELL).
 *
 * Восемь живых конфликтных клеток кадра 04 (замер 20.08: чтение «В Excel» сдвинуло
 * колонки, 6 из 25 ячеек). Чтение №1 — сегодняшний Excel-результат, посеянный как
 * сомнительные ячейки; дальше — обычные витки «Понять» той же спиралью: бриф с адресами,
 * ротация исполнителей, спор, большинство накопленных прочтений, согласие-улики.
 * Отдельного конвейера нет: проба зовёт UnderstandRealizer как есть.
 *
 * Живая сеть; в CI пропускается без POINT_CELL_PROBE. Запуск:
 *   POINT_CELL_PROBE=1 ./gradlew :executors:testDebugUnitTest --tests "*AddressableCellProbe*" --rerun
 */
class AddressableCellProbe {

    /** Адрес → (эталон, что прочёл Excel 20.08). Адреса — строки данных сверху, без шапки. */
    private val cells = mapOf(
        (1 to 4) to ("11 625" to "Капуста білоголова свіжа"),
        (1 to 5) to ("9 300" to "11625"),
        (1 to 6) to ("116,25" to "9300"),
        (1 to 7) to ("93" to "116,25"),
        (2 to 4) to ("300" to "Олія соняшникова рафінована"),
        (2 to 6) to ("3" to "300"),
        (3 to 4) to ("500" to "Цукор"),
        (3 to 6) to ("5" to "500"),
    )

    @Test
    fun `восемь конфликтных клеток кадра 04 — до и после спирали`() {
        assumeTrue(System.getenv("POINT_CELL_PROBE") != null)
        val report = StringBuilder()
        fun say(line: String) {
            println(line)
            report.append(line).append(System.lineSeparator())
            System.getenv("POINT_CELL_REPORT")?.let { runCatching { File(it).writeText(report.toString()) } }
        }
        runBlocking {
            val corpus = File(System.getenv("POINT_CORPUS") ?: "C:/Users/User/point-corpus")
            val realizer = UnderstandRealizer(EyesOnlyCorpusProbe.liveChain())

            val seeded = buildMap {
                cells.forEach { (address, pair) ->
                    val key = cellKey(address.first, address.second)
                    put(key, pair.second)
                    put(key + META_ACTOR_SUFFIX, "excel")
                    put(key + META_SOURCE_SUFFIX, Provenance.MODEL.wire)
                    put(key + META_EVIDENCE_SUFFIX, "")
                }
            }
            var obj = PointObject(
                "04", "image/jpeg",
                ScratchRef(File(corpus, "04.jpg").absolutePath),
                ObjectState(ObjectKind.IMAGE),
                seeded,
            )

            val before = cells.count { (address, pair) ->
                sameFact(cellKey(address.first, address.second), pair.first, pair.second)
            }
            say("До спирали: совпадает с эталоном $before из ${cells.size}")

            var rounds = 0
            var dry = 0
            while (rounds < 5 && dry < 2) {
                rounds++
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
                val cellsTouched = obj.metadata.keys.count {
                    it.startsWith("cell.") && obj.metadata[it] != beforeMeta[it]
                }
                say("виток $rounds · ${(outcome as ActionResult.Done).message} · ячеечных изменений: $cellsTouched")
                dry = if (rounds > 1 && com.point.core.flow.spiralDelta(beforeMeta, obj.metadata) == null) dry + 1 else 0
            }

            say("")
            var after = 0
            cells.forEach { (address, pair) ->
                val key = cellKey(address.first, address.second)
                val value = obj.metadata[key].orEmpty()
                val hit = sameFact(key, pair.first, value)
                if (hit) after++
                val marks = obj.metadata[key + META_EVIDENCE_SUFFIX].orEmpty()
                val spor = if (obj.metadata[key + META_ALT_SUFFIX].isNullOrBlank()) "" else " · спор жив"
                say(
                    "r${address.first}c${address.second} · эталон «${pair.first}» · было «${pair.second}» · " +
                        "стало «$value» · " + (if (hit) "ВЕРНО" else "мимо") +
                        (if (marks.contains(AGREE_MARK)) " · подтверждено: $marks" else "") + spor,
                )
            }
            say("")
            say("После спирали: совпадает с эталоном $after из ${cells.size} (было $before)")
        }
    }
}

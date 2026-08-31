package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.GraphState
import com.point.core.flow.Latency
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.SOURCE_CAMERA
import com.point.core.flow.extensionForFile
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * «Снять ещё страницу» (#1042, решение владельца 15.08.2026).
 *
 * Режим съёмки до съёмки не выбирается: камера у Point одна, и что снято, видно только
 * после снимка. Поэтому предложение приходит ПОСЛЕ объекта — когда первое чтение показало
 * на снимке текст, то есть человек снял лист. Одиночный снимок от этого ничего не теряет:
 * это одна строка в списке действий, а не режим, из которого надо выходить.
 *
 * Снятое собирается набором — тем самым, что уже умеет #1207: превью страниц, перестановка,
 * «Сканировать в PDF», «В Excel». Порядок страниц у свежего набора — порядок съёмки, и он
 * записан именами файлов; знание `collection.order` появляется, когда человек переставит
 * страницы сам. Своего порядка здесь не заводится.
 *
 * Предложение стоит и на самом наборе: пачка — это не две страницы, и следующий снимок
 * ложится в тот же набор, а не рождает второй. Набор при этом остаётся тем же объектом —
 * та же папка, — и Point продолжает его, а не заводит рядом копию.
 */
class ShootMoreCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "camera"

    override val meta = CapabilityMeta(latency = Latency.FAST)

    override fun label(state: ObjectState) = "Снять ещё страницу"

    /**
     * Снят лист — это снимок, на котором Point прочитал текст: нулевой сигнал (это картинка)
     * и первое чтение. Ни нового признака, ни нового типа объекта для этого не нужно.
     */
    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.IMAGE && state.has(Feature.HAS_TEXT)

    /** На наборе — только на том, который Point собрал этими же снимками, а не на любом. */
    override fun accepts(graph: GraphState) = accepts(graph.state) || isShotPages(graph)

    override fun produces(state: ObjectState) = ObjectState(ObjectKind.COLLECTION)

    override fun yields(state: ObjectState) = ActionYield.New(ObjectKind.COLLECTION, "набор страниц")

    companion object { val ID = CapabilityId("shoot-more") }
}

/** Пометка набора, собранного съёмкой: по ней предложение возвращается к своей же пачке. */
internal const val OP_SHOT_PAGES = "shoot-more"

/** Имя набора не считает страницы: оно не меняется от каждого снимка и потому не спорит само с собой. */
internal const val SHOT_PAGES_NAME = "Страницы"

internal fun isShotPages(graph: GraphState): Boolean =
    graph.state.kind == ObjectKind.COLLECTION && graph.fact("op") == OP_SHOT_PAGES

class ShootMoreRealizer @Inject constructor(
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = ShootMoreCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {

        // Незавершённый шаг, а не исход (ADR-0001 §18): Point ждёт снимка именно с камеры —
        // человек показывает следующий лист, а не ищет готовую картинку в галерее.
        if (amendment == null) return ActionResult.NeedsImage(ASK, from = SOURCE_CAMERA)

        return withContext(Dispatchers.IO) {
            runCatching {
                reportStage("Кладу страницу в набор")
                val dir = pagesDir(input)
                val shot = File(store.ingest(amendment, "image/jpeg").uri.value)
                val page = File(dir, pageName(pagesIn(dir) + 1, extensionForFile(shot.name, "image/jpeg")))

                // Копия снимка уже в scratch — в набор она переезжает, а не копируется ещё раз.
                if (!shot.renameTo(page)) {
                    shot.copyTo(page, overwrite = true)
                    shot.delete()
                }
                ActionResult.Success(
                    ResultObject(
                        ObjectKind.COLLECTION,
                        "inode/directory",
                        ScratchRef(dir.absolutePath),
                        mapOf("op" to OP_SHOT_PAGES, "name" to SHOT_PAGES_NAME),
                    ),
                )
            }.getOrElse {

                // Чужой текст исключения человеку не показывается (#1225): что именно не
                // записалось на диск, ему не поможет, а сделать шаг заново можно.
                ActionResult.Failure(com.point.core.flow.ownWordsOf(it) ?: FAILED, recoverable = true)
            }
        }
    }

    /**
     * Папка набора: у пачки она своя и уже есть, а у одиночного снимка её только заводят —
     * и первой страницей в неё ложится копия самого снимка. Копия, а не переезд: снимок
     * остаётся объектом человека, и знание, которое Point о нём уже получил, никуда не
     * девается.
     */
    private suspend fun pagesDir(input: PointObject): File {
        if (input.state.kind == ObjectKind.COLLECTION) return File(input.uri.value).apply { mkdirs() }
        val dir = File(store.newScratchFile("pages").value).apply { mkdirs() }
        val first = pageName(1, extensionForFile(input.metadata["name"], input.mime))
        File(input.uri.value).copyTo(File(dir, first), overwrite = true)
        return dir
    }

    private fun pagesIn(dir: File): Int = dir.listFiles()?.count { it.isFile } ?: 0

    /**
     * Имя страницы — её место в пачке. Порядок съёмки читается прямо из имён (#1207: «имя
     * файла остаётся запасным порядком — для страниц, о которых знания нет»), поэтому номер
     * пишется с ведущим нулём: без него десятая страница встала бы между первой и второй.
     */
    private fun pageName(number: Int, extension: String): String =
        "Страница ${number.toString().padStart(2, '0')}.${extension.ifBlank { "jpg" }}"

    private companion object {

        const val ASK = "Снимите следующую страницу"

        const val FAILED = "Не удалось добавить страницу в набор"
    }
}

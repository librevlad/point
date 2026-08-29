package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.META_COLLECTION_ORDER
import com.point.core.flow.ObjectStore
import com.point.core.flow.OfficeTextExtractor
import com.point.core.flow.Realizer
import com.point.core.flow.collectionOrderValue
import com.point.core.flow.officeTextMissingReason
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
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
 * «Слайды» — то же для презентации, что «Страницы» для PDF (#1105, решение владельца 23.08.2026).
 *
 * Презентация из трёх слайдов была одним объектом с одним сплошным текстом всех слайдов
 * подряд: сумма с первого слайда и контакт со второго лежали общей кучей, и сказать, где что
 * сказано, было нечем. Слайд — часть объекта, и раскладывается он тем же механизмом, каким
 * раскладывается документ: набор частей, в который человек входит.
 *
 * Из чего документ состоит, видно по нулевому сигналу — имени и mime (`IS_PRESENTATION`), —
 * поэтому дверь стоит на первом экране, не дожидаясь ни одного прочитанного байта.
 */
class SlidesCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "pages"

    // Слайды лежали внутри презентации, а не сделаны заново, — связь с исходником «содержит».
    override val meta = CapabilityMeta(latency = Latency.FAST, revealsInside = true)
    override fun label(state: ObjectState) = "Слайды"

    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.OFFICE && state.has(Feature.IS_PRESENTATION)

    override fun produces(state: ObjectState) = ObjectState(ObjectKind.COLLECTION)

    companion object { val ID = CapabilityId("slides") }
}

class SlidesRealizer @Inject constructor(
    private val store: ObjectStore,
    private val officeText: OfficeTextExtractor,
) : Realizer {
    override val capabilityId = SlidesCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage(SPLIT_STAGE)
                val slides = officeText.slides(input)

                // Слайд без текста частью не становится: войти в него нечем, а пустой
                // непригодный кусок в наборе — мусор. Номер при этом остаётся настоящим:
                // текста нет на втором слайде — третий так и зовётся третьим.
                val parts = slides.withIndex().filter { (_, text) -> text.isNotBlank() }
                if (parts.isEmpty()) {

                    // Читать нечего — и причина называется та, что есть на самом деле
                    // (#997): у старого .ppt это формат, у презентации из одних картинок —
                    // отсутствие текста.
                    ActionResult.Failure(
                        officeTextMissingReason(input.metadata["name"], input.mime),
                        recoverable = false,
                    )
                } else {
                    val dir = File(store.newScratchFile("slides").value).apply { mkdirs() }
                    val names = parts.map { (index, text) ->
                        slideName(index + 1).also { File(dir, it).writeText(text) }
                    }
                    ActionResult.Success(
                        ResultObject(
                            ObjectKind.COLLECTION,
                            "inode/directory",
                            ScratchRef(dir.absolutePath),
                            mapOf(
                                "op" to "slides",
                                "count" to names.size.toString(),

                                // Порядок слайдов — знание самого набора (#1207): по имени
                                // «Слайд 10» встал бы между первым и вторым.
                                META_COLLECTION_ORDER to collectionOrderValue(names),
                            ),
                        ),
                    )
                }
            }.getOrElse { ActionResult.Failure(NOT_SPLIT, recoverable = true) }
        }

    internal companion object {

        /** Имя части — то, что человек читает в наборе: слайд и его номер в презентации. */
        fun slideName(number: Int): String = "Слайд $number.txt"

        const val SPLIT_STAGE = "Разбираю презентацию на слайды"

        const val NOT_SPLIT = "Не удалось разобрать презентацию на слайды"
    }
}

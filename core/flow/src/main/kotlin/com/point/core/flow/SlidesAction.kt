package com.point.core.flow

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
class SlidesCapability : Capability {
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

class SlidesRealizer(
    private val store: ObjectStore,
    private val officeText: OfficeTextExtractor,
) : Realizer {
    override val capabilityId = SlidesCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage(SPLIT_STAGE)
                val slides = officeText.slides(input)
                when {

                    // Слайдов не нашлось вовсе. У старого .ppt виной формат — Point его не
                    // открывает совсем (#997); у прочих файлов не удался сам разбор, и это
                    // про попытку, а не про документ.
                    slides.isEmpty() && !isModernOffice(input.metadata["name"], input.mime) ->
                        ActionResult.Failure(OLD_OFFICE_FORMAT, recoverable = false)

                    slides.isEmpty() -> ActionResult.Failure(NOT_SPLIT, recoverable = true)

                    // Слайды есть, а слов на них нет: человек нажал «Слайды», и отвечать ему
                    // «в этом документе текста нет» значит говорить не о том, о чём спросили.
                    slides.none { (_, text) -> text.isNotBlank() } ->
                        ActionResult.Failure(NO_WORDS_ON_SLIDES, recoverable = false)

                    else -> collection(slides)
                }
            }.getOrElse { ActionResult.Failure(NOT_SPLIT, recoverable = true) }
        }

    /**
     * Набор частей, где слайд остаётся слайдом.
     *
     * Слайд без текста из набора не выкидывается (§13 и инвариант 13): «слов не нашлось» —
     * это не «такого слайда не существует», а войти в слайд с одной фотографией и продолжить
     * понимание (фотография → автомобиль → госномер) человек вправе. Пустая часть честно
     * непригодна сама: нулевой размер `ObjectStore` называет своими словами, как у любого
     * пустого файла.
     */
    private suspend fun collection(slides: List<Pair<Int, String>>): ActionResult {
        val dir = File(store.newScratchFile("slides").value).apply { mkdirs() }
        val names = slides.map { (number, text) ->
            slideName(number).also { File(dir, it).writeText(text) }
        }
        return ActionResult.Success(
            ResultObject(
                ObjectKind.COLLECTION,
                "inode/directory",
                ScratchRef(dir.absolutePath),
                mapOf(
                    "op" to "slides",
                    "count" to names.size.toString(),

                    // Порядок слайдов — знание самого набора (#1207): по имени «Слайд 10»
                    // встал бы между первым и вторым.
                    META_COLLECTION_ORDER to collectionOrderValue(names),
                ),
            ),
        )
    }

    companion object {

        /** Имя части — то, что человек читает в наборе: слайд и его номер в презентации. */
        fun slideName(number: Int): String = "Слайд $number.txt"

        const val SPLIT_STAGE = "Разбираю презентацию на слайды"

        /** Что случилось — и что дальше: дверь «Открыть» у презентации есть всегда. */
        const val NOT_SPLIT =
            "Не удалось разобрать презентацию на слайды — откройте её целиком, чтобы посмотреть глазами"

        /**
         * Колода из одних картинок: слайды у человека есть, слов на них нет (#1105).
         *
         * Прежде здесь звучало «в этом документе текста нет» — ответ про текст на вопрос про
         * слайды, да ещё и отменяющий сами слайды.
         */
        const val NO_WORDS_ON_SLIDES =
            "Слайды есть, а текста на них нет — Point достаёт из презентации только слова. " +
                "Откройте её целиком, чтобы посмотреть глазами"
    }
}

package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * «Найти в документе» — там, где есть что читать (#1014, решения владельца 15.08 и 19.08.2026).
 *
 * Прежде действие жило только у страницы со слоем слов: признак `HAS_WORD_LAYER` — свойство
 * того, **как** ищут (подсветкой на снимке), а не того, **что** можно сделать. Скан-PDF после
 * «Прочитать документ» знал свой текст, а искать в нём было нельзя — действия не было вовсе, и
 * подсказка звала «разложить на страницы». Теперь у прочитанного PDF текст — знание самого
 * документа, и поиск идёт по нему; у страницы со слоем слов — по-прежнему по слою.
 */
class FindCapability : Capability {
    override val id = ID
    override val icon = "find"

    override val meta = CapabilityMeta(priority = 40, latency = Latency.INSTANT)

    override fun label(state: ObjectState) = tr("Найти в документе")

    override fun accepts(state: ObjectState) =
        Feature.HAS_WORD_LAYER in state.features ||
            (state.kind == ObjectKind.PDF && state.has(Feature.HAS_TEXT))

    override fun produces(state: ObjectState) = state

    override fun intents(state: ObjectState) = setOf(Intent.UNDERSTAND)

    /**
     * Непрочитанному PDF подсказка называет тот шаг, который у документа на месте (#1014,
     * #1257): скану — «Прочитать документ», документу со своим слоем текста — «Извлечь текст».
     */
    override fun missing(state: ObjectState): String? = when {
        state.kind != ObjectKind.PDF -> null
        state.has(Feature.IS_IMAGE_PDF) -> tr("сначала прочитайте документ")
        else -> tr("сначала извлеките текст")
    }

    companion object { val ID = CapabilityId("find") }
}

/**
 * Ищется ли запрос подсветкой на самой странице, а не по тексту документа (#1014).
 *
 * Страница ищется по своему слою слов. PDF — тоже, если слой у него есть; прочитанный скан
 * слоя не имеет, у него только текст, и поиск по нему отвечает словами: сколько мест и где.
 *
 * Одно правило на исполнителя и на экран телефона: телефон открывает поиск-подсветку ровно
 * тогда, когда исполнитель искал бы по слою, а иначе спрашивает запрос обычным путём действия.
 */
fun findShowsOnPage(obj: PointObject): Boolean =
    obj.metadata[META_OCR_ATOMS_REF] != null || obj.state.kind != ObjectKind.PDF

class FindRealizer : Realizer {
    override val capabilityId = FindCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            val query = amendment.orEmpty()

            if (!isSearchable(query)) return@withContext ActionResult.NeedsInput(tr("Что найти в документе?"))
            if (findShowsOnPage(input)) onPage(input, query) else inDocument(input, query)
        }

    private fun onPage(input: PointObject, query: String): ActionResult {
        val layer = atomLayer(input)
            ?: return ActionResult.Failure(tr("Страница ещё не прочитана — искать не в чем"), recoverable = false)
        return ActionResult.Done(foundOnPageLabel(layer.findOnPage(query).size))
    }

    /**
     * Поиск по тексту документа (#1014): сколько мест и на каких страницах.
     *
     * Текста нет или он не открылся — это отказ операции словами про документ, а не «ничего не
     * нашлось»: искать было не в чем, и выдать это за ответ значило бы солгать о документе.
     *
     * Текст доехал с другого устройства не целиком — ответ говорит, докуда искали
     * ([foundInPartLabel]): по одному началу «ничего не нашлось» было бы той же ложью.
     *
     * Сам документ прочитан не целиком — ответ говорит о прочитанных страницах
     * ([foundInReadPagesLabel]): «Ничего не нашлось» по скану, у которого прочитана сотня
     * страниц из 110, — ответ о хвосте, которого никто не видел.
     */
    private fun inDocument(input: PointObject, query: String): ActionResult {
        val ref = textRefForTravel(input.metadata)
            ?: return ActionResult.Failure(tr("Документ ещё не прочитан — искать не в чем"), recoverable = false)
        val text = runCatching { File(ref).readText() }.getOrNull()
            ?: return ActionResult.Failure(
                tr("Прочитанный текст документа не открылся — искать не в чем"),
                recoverable = false,
            )
        val hits = findInText(text, query)
        val starts = pageStartsOf(input.metadata)
        val pages = pagesOf(hits, starts)
        val cut = textCutShort(input.metadata, text.length)
        val said = if (cut) {
            foundInPartLabel(hits.size, pages, pageCutAt(starts, text.length))
        } else {
            foundInDocumentLabel(hits.size, pages)
        }
        return ActionResult.Done(foundInReadPagesLabel(said, hits.size, cut, input.metadata))
    }

    private fun atomLayer(input: PointObject): AtomLayer? =
        input.metadata[META_OCR_ATOMS_REF]?.let { ref ->
            runCatching { AtomCodec.decode(File(ref).readText()) }.getOrNull()
        }
}

package com.point.executors

import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.META_CLOUD_ATOMS_REF
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.Realizer
import com.point.core.flow.findOnPage
import com.point.core.flow.foundOnPageLabel
import com.point.core.flow.isSearchable
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * «Найти в документе» (#279) — тот же адрес значения, только запрос печатает человек.
 *
 * Предлагается **только там, где есть слой слов** ([Feature.HAS_WORD_LAYER]): страница, которую
 * уже прочитали и разложили по местам. Действие, которое «ищет и не находит» на объекте без
 * прочитанных слов, обещало бы поиск и врало бы про результат — поэтому его там просто нет.
 *
 * Отдельного пути к пикселям PDF действие не заводит: страницы PDF уже растрируются
 * «Страницами» ([PagesCapability]), каждая становится обычной картинкой, читается тем же
 * движком и получает свой слой слов. Поэтому на самом PDF действие не появляется, а говорит
 * вслух, чего ему не хватает ([missing]) — один шаг, который человек делает сам.
 */
class FindCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "find"

    /** Местное и мгновенное: строки уже прочитаны, ищется в памяти. */
    override val meta = CapabilityMeta(priority = 40, latency = Latency.INSTANT)

    override fun label(state: ObjectState) = "Найти в документе"

    override fun accepts(state: ObjectState) = Feature.HAS_WORD_LAYER in state.features

    /** Нового объекта не появляется: находки показываются на этой же странице. */
    override fun produces(state: ObjectState) = state

    /** Терминальное по форме, но человек им **понимает** документ, а не отправляет его. */
    override fun intents(state: ObjectState) = setOf(Intent.UNDERSTAND)

    override fun missing(state: ObjectState): String? =
        if (state.kind == ObjectKind.PDF) "разложите на страницы" else null

    companion object { val ID = CapabilityId("find") }
}

/**
 * Поиск без экрана.
 *
 * Человеку места показывает экран поиска (host перехватывает тап, как у «Открыть в…» и чата):
 * подсветить находку можно только там, где видно страницу. Реализатор отвечает на **тот же
 * вопрос** там, где экрана нет, — и оба считают одной и той же чистой функцией
 * ([findOnPage]), поэтому разойтись в ответе им нечем.
 *
 * «Не нашлось» — это [ActionResult.Done], а не отказ: страница прочитана, ответ получен, и
 * красить честное «здесь этого нет» в цвет ошибки значило бы врать вторым сообщением после
 * первого (#358).
 */
class FindRealizer @Inject constructor() : Realizer {
    override val capabilityId = FindCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            val query = amendment.orEmpty()
            // Пустая строка и строка из одного оформления («—», «...») — одинаково «не спросили»:
            // правило одно на весь поиск и живёт в ядре.
            if (!isSearchable(query)) return@withContext ActionResult.NeedsInput("Что найти в документе?")
            val layer = atomLayer(input)
                ?: return@withContext ActionResult.Failure(
                    "Страница ещё не прочитана — искать не в чем",
                    recoverable = false,
                )
            val found = layer.findOnPage(query)
            ActionResult.Done(foundOnPageLabel(found.size))
        }

    /** Слой слов страницы: офлайновый, а при его отсутствии — облачный (#280). Битый дамп —
     *  не находка и не тишина: вызывающий получит честное «искать не в чем». */
    private fun atomLayer(input: PointObject): AtomLayer? =
        (input.metadata[META_OCR_ATOMS_REF] ?: input.metadata[META_CLOUD_ATOMS_REF])?.let { ref ->
            runCatching { AtomCodec.decode(File(ref).readText()) }.getOrNull()
        }
}

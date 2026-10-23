package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Lang
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * На какой язык переводить, если человек не сказал (#1473): на его собственный, а если текст
 * уже на нём — на английский. Англичанину чужой латинский текст переводится на русский, как и
 * раньше: другой цели у прежнего правила не было.
 *
 * Название языка — русское слово для промпта; человеку его показывает [tr].
 */
internal fun translateDefaultTarget(text: String, lang: Lang = Lang.current()): String =
    when (translateTargetLang(text, lang)) {
        Lang.EN -> "английский"
        Lang.UK -> "украинский"
        Lang.RU -> "русский"
    }

/** Название языка для человека — в подписи «Перевожу на …». */
internal fun languageName(lang: Lang): String = when (lang) {
    Lang.EN -> tr("английский")
    Lang.UK -> tr("украинский")
    Lang.RU -> tr("русский")
}

internal fun translateTargetLang(text: String, lang: Lang): Lang {
    val cyrillic = text.count { it in 'а'..'я' || it in 'А'..'Я' || it in "ёЁіІїЇєЄґҐ" }
    val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
    val textLang = when {
        latin >= cyrillic -> Lang.EN
        text.any { it in "іІїЇєЄґҐ" } -> Lang.UK
        else -> Lang.RU
    }
    return when {
        textLang != lang -> lang
        lang == Lang.EN -> Lang.RU
        else -> Lang.EN
    }
}

class TranslateCapability(
    private val keys: AiReadiness,
) : Capability {
    override val id = ID
    override val icon = "translate"
    override val meta = CapabilityMeta(latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = labelNeedingKey(tr("Перевести"), keys.keySet())
    // Переводить есть что там, где есть текст, — а не там, где объект нужного вида (#792):
    // прочитанный снимок это объект с текстом, и требовать распознать его второй раз незачем.
    override fun accepts(state: ObjectState) =
        state.kind in setOf(ObjectKind.TEXT, ObjectKind.PDF) || state.has(Feature.HAS_TEXT)
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    /**
     * Чего не хватает, чтобы переводить, — говорит само действие (#996).
     *
     * Не только у снимка: PDF без единого прочитанного слова переводить так же нечем, и
     * прежде он молчал об этом, стоя главным действием экрана.
     */
    override fun missing(state: ObjectState) = when {
        state.has(Feature.HAS_TEXT) || state.kind == ObjectKind.TEXT -> null
        state.kind == ObjectKind.IMAGE -> tr("сначала распознайте текст")
        else -> tr("сначала извлеките текст")
    }

    companion object { val ID = CapabilityId("translate") }
}

class TranslateRealizer(
    private val llm: LlmClient,
    private val known: com.point.core.flow.CurrentKnowledge,
) : Realizer {
    override val capabilityId = TranslateCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {

                // Переводится то, что Point знает сейчас, а не то, с чего он начал (#1030,
                // #1138). Прочитанный кадр отвечал «Нет текста для перевода», хотя чтение
                // лежало в графе и человек видел его на экране.
                val text = known.textOf(input).orEmpty()
                if (text.isBlank()) {
                    ActionResult.Failure(tr("Нет текста для перевода"), recoverable = true)
                } else {
                    val target = amendment?.takeIf { it.isNotBlank() } ?: translateDefaultTarget(text)
                    val prompt = "Переведи текст на $target. Верни только перевод, без пояснений.\n\n$text"

                    reportStage(tr("Перевожу на {0}", tr(target)))

                    // Переводятся символы, а не пиксели (#1244): текст уже в запросе, и
                    // снимок или PDF рядом с ним — лишние байты наружу и отброшенные
                    // текстовые модели, которые перевели бы мгновенно.
                    ActionResult.Success(llm.run(textStandIn(input), prompt))
                }
            }.getOrElse { ActionResult.Failure(it.message ?: tr("Ошибка перевода"), recoverable = true) }
        }
}

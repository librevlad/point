package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Findings
import com.point.core.model.PointObject

import com.point.core.model.tr
class FallbackRealizer(
    override val capabilityId: CapabilityId,
    private val chain: List<Realizer>,
) : Realizer {

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        var last: ActionResult = ActionResult.Failure(NOBODY_TO_DO_IT, recoverable = true)
        for ((index, realizer) in chain.withIndex()) {
            val result = realizer.perform(input, amendment)
            last = result
            if (!defers(result) || index == chain.lastIndex) return result
        }
        return last
    }

    /**
     * Уступить очередь следующему, но не потерять сказанное.
     *
     * Сорвавшуюся попытку сменяет следующая — так было всегда. Так же уступает и честное
     * «смотрели — не нашлось» (#1054): слабый читатель отвечал «текста нет», и зрячая модель
     * за ним уже не пробовала — вопрос закрывался ответом того, кто видит хуже всех. Когда
     * посмотрели все и никто не нашёл, ответом остаётся последнее «не нашлось» — своими
     * словами и своим знанием, а не отказом. Сорвался последний — срыв и остаётся: он мог
     * увидеть то, чего не увидели до него, и чужое «не нашлось» вопрос за него не закрывает
     * (ADR-0001 §9).
     */
    private fun defers(result: ActionResult): Boolean = when (result) {
        is ActionResult.Failure -> result.recoverable
        is ActionResult.Done -> onlyNotFound(result.findings)
        else -> false
    }

    /**
     * Шаг принёс одно только «смотрели — не нашлось» и ничего больше: ответ, но не находка.
     *
     * Узнанное о самом объекте очередь закрывает — и правильно (#1053): телефон разбирает
     * запись до сэмплов и намеряет тишину, это знание о содержимом, а не о том, кто как
     * слушал. Следующему исполнителю достанутся те же байты и та же тишина; уступи ему
     * очередь — и пустая запись уедет на компьютер, а оттуда в сервис, за выдумкой, ради
     * которой её и слушали.
     */
    private fun onlyNotFound(findings: Findings?): Boolean {
        val told = findings ?: return false
        if (told.features.isNotEmpty() || told.objects.isNotEmpty() || told.relations.isNotEmpty()) return false
        return told.metadata.keys.any(::isStateKey) &&
            told.metadata.all { (key, value) ->
                (isStateKey(key) && value == InvestigationState.NOT_FOUND.wire) || key in HOW_IT_WAS_LOOKED
            }
    }

    companion object {

        /**
         * Пометки о том, как смотрели, а не о том, что увидели: с ними «не нашлось» остаётся
         * одним только «не нашлось».
         *
         * Чтение документа кладёт рядом со своим ответом счёт просмотренных страниц
         * ([META_OCR_TEXT_COVERAGE], карточка «Не отвечать «Ничего не нашлось» по скану,
         * прочитанному не целиком»). Это знание о проходе этого читателя — какие страницы он
         * довёл до ответа, — а не о содержимом: следующий читает те же страницы заново и
         * приносит свой счёт, который встаёт на место прежнего ([REFRESHABLE_KNOWLEDGE]). Без
         * этой оговорки рукописный скан, на котором движок телефона не увидел текста, больше не
         * уходил к зрячему читателю компьютера: «не нашлось» со счётом очередь принимала за
         * находку и закрывала вопрос ответом того, кто видит хуже всех (#1054).
         */
        private val HOW_IT_WAS_LOOKED = setOf(META_OCR_TEXT_COVERAGE)

        val NOBODY_TO_DO_IT: String get() = tr("Это действие сейчас выполнить нечем — вернитесь к объекту и выберите другое")
    }
}

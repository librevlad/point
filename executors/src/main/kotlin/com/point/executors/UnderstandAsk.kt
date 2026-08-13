package com.point.executors

import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.CLASSIFIER_ROLES
import com.point.core.flow.AiReadiness
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.ClassifierRole
import com.point.core.flow.Cost
import com.point.core.flow.EvidenceClass
import com.point.core.flow.FieldCandidate
import com.point.core.flow.InvestigationState
import com.point.core.flow.KNOWN_SEMANTIC_TAGS
import com.point.core.flow.Latency
import com.point.core.flow.LayoutElement
import com.point.core.flow.LlmClient
import com.point.core.flow.MAX_FIELD_CANDIDATES
import com.point.core.flow.META_ALT_SUFFIX
import com.point.core.flow.META_BLOCKED_SUFFIX
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_EVIDENCE_SUFFIX
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_READ_CHARS
import com.point.core.flow.META_READ_TOTAL_CHARS
import com.point.core.flow.investigationOutcome
import com.point.core.flow.partialReadMessage
import com.point.core.flow.readProgressOf
import com.point.core.flow.readWindowOf
import com.point.core.flow.withInvestigation
import com.point.core.flow.ReadingMode
import com.point.core.flow.META_READING_MODE
import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.flow.META_SEMANTIC_TYPE
import com.point.core.flow.META_SOURCE_SUFFIX
import com.point.core.flow.ParsedUnderstanding
import com.point.core.flow.Realizer
import com.point.core.flow.UNDERSTAND_CONTRACT_KEYS
import com.point.core.flow.parseFieldCandidates
import com.point.core.flow.saysNothing
import com.point.core.flow.splitCandidate
import com.point.core.flow.AtomAddress
import com.point.core.flow.altValue
import com.point.core.flow.alternativesOf
import com.point.core.flow.bareIndexId
import com.point.core.flow.fieldEvidence
import com.point.core.flow.formEvidence
import com.point.core.flow.isRepairOf
import com.point.core.flow.isRoleLabel
import com.point.core.flow.layoutOf
import com.point.core.flow.mergeFacts
import com.point.core.flow.normConsensus
import com.point.core.flow.parseClassification
import com.point.core.flow.promptIndex
import com.point.core.flow.reportStage
import com.point.core.flow.provenanceOf
import com.point.core.flow.resolve
import com.point.core.flow.ruleEvidence
import com.point.core.flow.s10CheckDigitValid
import com.point.core.flow.META_ENTITY_PLACE
import com.point.core.flow.placeOfReceiver
import com.point.core.flow.phoneOwners
import com.point.core.flow.foundLiterally
import com.point.core.flow.standsInReadText
import com.point.core.flow.semanticFits
import com.point.core.flow.labelNeedingKey
import com.point.core.model.ActionResult
import com.point.core.flow.KIND_PERSON
import com.point.core.flow.plausiblePersonName
import com.point.core.model.Findings
import com.point.core.model.Relation
import com.point.core.model.RelationType
import com.point.core.model.ValueRef
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Спросить: что уходит модели и в каком виде (#835).
 *
 * Жило в `UnderstandAction.kt` вместе с разбором ответа и судейством — 772 строки, где
 * правило «нет в тексте — нет знания» правилось там же, где собирается текст запроса.
 */
/**
 * Роль можно не называть вовсе. Без этой оговорки модель выбирала ближайшую из
 * предложенных, и студия на визитке оказывалась «выдавшей документ» (#698).
 */
internal const val ROLES_MAY_BE_ABSENT =
    "Ролей может не быть ни одной: визитка, вывеска, меню, фотография — не документ " +
        "с отправителем или выдавшим. Ни одной роли лучше, чем натянутая.\n"

internal fun understandPrompt(
    elements: List<LayoutElement>,
    roles: List<ClassifierRole> = CLASSIFIER_ROLES,
    index: String? = null,
): String = buildString {
    append("Текст распознан с фотографии и может содержать ошибки распознавания. ")
    append("Ниже его элементы, у каждого свой идентификатор.\n\n")

    // Блоки страницы названы прямо (#768). На почтовой наклейке подпись правой колонки
    // «КОМУ:» стоит чуть выше подписи левой «ВІД:» — кадр снят под наклоном, — и сплошным
    // списком строк это читается как «сначала КОМУ, потом Тарасенко»: отправитель с
    // получателем менялись местами. Блок держит подпись при своей колонке.
    val blocks = elements.groupBy { it.block }
    if (blocks.size > 1) {
        append("Страница разбита на блоки — колонки, шапка, подвал. Значение и подпись к нему ")
        append("ищи внутри одного блока: соседний блок про другое.\n\n")
        blocks.forEach { (number, group) ->
            append("Блок ").append(number + 1).append(":\n")
            group.forEach { append(it.id).append(": ").append(it.text).append('\n') }
            append('\n')
        }
    } else {
        elements.forEach { append(it.id).append(": ").append(it.text).append('\n') }
    }
    if (index != null) {
        append(
            "\nСлова страницы, каждое с меткой (атрибут rule= — подсказка офлайн-правила о " +
                "форме слова; она может ошибаться и ничего не решает):\n",
        )
        append(index).append('\n')
    }
    append("\nСделай две вещи.\n\n")
    append(
        "1) Найди контактные данные и номера. Значение приводи ПОЛНОСТЬЮ, как оно есть в " +
            "документе (адрес — вместе с населённым пунктом), и исправляй только явные искажения " +
            "распознавания. НИЧЕГО не додумывай: если чего-то в тексте нет — не пиши строку. " +
            "Цифры не меняй. Отвечай строками вида KEY=значение, по одной на строку. " +
            "Разрешённые KEY: PHONE, EMAIL, URL, ADDRESS, DATE, CARD, TRACK " +
            "(номер отправления/накладной, дословно), METER (показание счётчика — ТОЛЬКО цифры " +
            "показания, без единицы измерения), GEO (координаты точки — широта и долгота), PLACE (куда ехать, если адреса " +
            "нет: название отделения, магазина, населённого пункта — дословно с экрана), " +
            "AMOUNT (сумма к оплате или переводу — ТОЛЬКО цифры, без валюты), " +
            "RECEIPT (номер квитанции или чека, дословно), " +
            "SUBJECT (тема письма или сообщения; если это не письмо и не переписка — не пиши), " +
            "CONTACT (телефон вместе с именем его владельца: <номер> | <имя>). ",
    )
    if (index != null) {
        append(
            "После значения укажи метки его слов в квадратных скобках: " +
                "TRACK=20 4514 9154 9395 [w3 w4 w5]. Если слова значения есть в списке — метки " +
                "ОБЯЗАТЕЛЬНЫ; текст без меток — только когда слов в списке нет. ",
        )
    }
    append(
        "Если ты не уверен, каких кандидатов на поле несколько — перечисли до " +
            "$MAX_FIELD_CANDIDATES строк с одним KEY, лучший первым; не выдумывай кандидатов " +
            "ради количества. " +

            // #653, решение владельца: «просто дергай контакты и по возможности
            // связывай их с именами, ллм это умеет».
            "ВАЖНО про телефоны: если рядом с номером в тексте стоит имя его владельца, " +
            "вместо PHONE дай строку CONTACT=<номер> | <имя полностью>, по одной на каждого " +
            "человека. Пример: CONTACT=+380 66 526 2706 | Іваненко Іван Петрович. " +
            "Имя пиши правильно, исправляя явные искажения распознавания; должность и звание " +
            "в имя не включай. Только когда имени рядом нет — оставь номер строкой PHONE. " +
            // Ярлыков-типов у модели больше не просим (#663): суть несёт SUMMARY.
            "Добавь строку SUMMARY=<суть текста в 3-6 словах, на языке документа>.\n\n",
    )
    // #698, решение владельца «Нет подходящей — нет подписи»: роль натягивалась на
    // визитку («выдал документ»), потому что выбора «ни одной» модели не давали.
    append("2) Определи, кто играет каждую из ролей:\n")
    roles.forEach { append("- ").append(it.key).append(" — ").append(it.question).append('\n') }
    append(ROLES_MAY_BE_ABSENT)
    if (index != null) {
        append(
            "Отвечай строками вида роль=имя [метки слов имени]. Метки — из списка слов страницы; " +
                "слово-подпись (например «Відправник:», «Отримувач») в метки НЕ включай — " +
                "только слова самого имени. " +

                "Само имя пиши ПРАВИЛЬНО, исправляя явные искажения распознавания " +
                "(цифра вместо похожей буквы, потерянная буква): в списке слов может стоять " +
                "«1ваненко ван», а имя — «Іваненко Іван». Не выдумывай другое имя. " +
                "Роль, которой в документе нет, пропусти.\n\n",
        )
    } else {
        append(
            "Отвечай строками вида роль=идентификатор. Идентификатор — РОВНО один из " +
                "перечисленных выше, а не текст элемента. Роль, которой в документе нет, пропусти.\n\n",
        )
    }
    append("Без пояснений. Если не нашлось вообще ничего — ответь ровно NONE.\n")
}

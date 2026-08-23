package com.point.executors

import com.point.core.flow.AiReadiness
import com.point.core.flow.AtomAddress
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.CLASSIFIER_ROLES
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.EvidenceClass
import com.point.core.flow.InvestigationState
import com.point.core.flow.KIND_PERSON
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
import com.point.core.flow.META_READING_MODE
import com.point.core.flow.META_READ_CHARS
import com.point.core.flow.META_READ_TOTAL_CHARS
import com.point.core.flow.META_ANSWERED_BY
import com.point.core.flow.META_SOURCE_SUFFIX
import com.point.core.flow.addActor
import com.point.core.flow.ReadingMode
import com.point.core.flow.Realizer
import com.point.core.flow.UNDERSTAND_CONTRACT_KEYS
import com.point.core.flow.altValue
import com.point.core.flow.alternativesOf
import com.point.core.flow.answerLanguageRule
import com.point.core.flow.bareIndexId
import com.point.core.flow.belongings
import com.point.core.flow.investigationOutcome
import com.point.core.flow.isRepairOf
import com.point.core.flow.isRoleLabel
import com.point.core.flow.labelNeedingKey
import com.point.core.flow.layoutOf
import com.point.core.flow.mergeFacts
import com.point.core.flow.normConsensus
import com.point.core.flow.parseClassification
import com.point.core.flow.parseFieldCandidates
import com.point.core.flow.partialReadMessage
import com.point.core.flow.partyNodeId
import com.point.core.flow.plausiblePersonName
import com.point.core.flow.promptIndex
import com.point.core.flow.provenanceOf
import com.point.core.flow.readProgressOf
import com.point.core.flow.readWindowOf
import com.point.core.flow.reportStage
import com.point.core.flow.resolve
import com.point.core.flow.splitCandidate
import com.point.core.flow.withInvestigation
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Findings
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.Relation
import com.point.core.model.RelationType
import com.point.core.model.ValueRef
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UnderstandCapability @Inject constructor(
    private val keys: AiReadiness,
) : Capability {
    override val id = ID
    override val icon = "ai"
    override val meta = CapabilityMeta(priority = 31, cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = labelNeedingKey("Понять", keys.keySet())

    // «Понять» может бесконечно обогащать граф (решение владельца, #1010): после витка
    // действие зовётся дальше — следующий заход идёт другой моделью и улучшает результат
    // обычным merge. «Сильнее» — у всякого уже заданного вопроса (#1176): и у «нашли»,
    // и у «недостаточно», и у спора, и у «не нашлось» — другая модель вправе найти.
    override fun label(graph: com.point.core.flow.GraphState): String =
        if (com.point.core.flow.investigationStateOf(graph.obj.metadata, ID) !=
            com.point.core.flow.InvestigationState.NOT_INVESTIGATED
        ) {
            labelNeedingKey("Понять сильнее", keys.keySet())
        } else {
            label(graph.state)
        }

    // Фото без распознанного текста — тоже понимается: реализатор при пустых
    // elements читает снимок глазами (readWithEyes). Дверь была заперта на HAS_TEXT,
    // и счётчики/накладные/рукописи оставались без пути (#664, прогон 2026-08-09).
    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.TEXT || state.kind == ObjectKind.IMAGE || state.has(Feature.HAS_TEXT)
    override fun produces(state: ObjectState) = state

    /** Обещание названо руками и принадлежит этому действию, а не типу исхода (#580, #734). */
    override fun yields(state: ObjectState) = ActionYield.Same(UNDERSTAND_NOTE)
    override fun intents(state: ObjectState) = setOf(Intent.UNDERSTAND)

    companion object {
        const val UNDERSTAND_NOTE = "найдёт суть, суммы, даты и контакты"

        val ID = CapabilityId("understand") }
}

class UnderstandRealizer @Inject constructor(
    private val llm: LlmClient,
) : Realizer {
    override val capabilityId = UnderstandCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {

                // #682/#683: объект читается частями по MAX_CHARS. already>0 значит, что
                // прошлое нажатие «Понять» уже прочитало начало — это окно продолжает
                // именно с того места, а не перечитывает его вслепую заново.
                val full = entitySourceText(input)
                val already = readProgressOf(input.metadata)
                val resuming = already > 0
                val window = readWindowOf(full, already, MAX_CHARS)
                val elements = layoutOf(window)

                if (elements.isEmpty()) {
                    return@withContext when {

                        // Продолжение дочитало объект до конца, новых слов не осталось.
                        // Итог судится по всему, что накопилось за прошлые окна — не только
                        // за это пустое: найденное раньше не становится «не найдено» только
                        // потому, что здесь добавить было нечего.
                        resuming -> ActionResult.Done(
                            NOTHING_NEW,
                            Findings(
                                metadata = investigationOutcome(input.metadata, cumulativeFactKeys(input.metadata))
                                    .orEmptyInvestigation(),
                            ),
                        )
                        input.state.kind == ObjectKind.IMAGE -> readWithEyes(input)
                        else -> ActionResult.Failure("Нет текста для понимания", recoverable = true)
                    }
                }
                val readSoFar = already + window.length
                val fullyRead = readSoFar >= full.length

                val layer = atomLayer(input)

                // Пока страница читается целиком за раз, элементы берутся блоками — тогда
                // подпись колонки остаётся при своей колонке (#768). Длинный объект идёт
                // окнами, и там блоков не собрать: окно режет страницу поперёк.
                val laidOut = layer
                    ?.takeIf { already == 0 && fullyRead }
                    ?.blockTexts()
                    ?.takeIf { it.size > 1 }
                    ?.let { com.point.core.flow.layoutOfBlocks(it) }
                    ?.takeIf { it.isNotEmpty() }
                    ?: elements

                val index = layer?.promptIndex()

                // Снимок уходит модели вместе со страницей, а не вместо неё (#770, решение
                // владельца 11.08.2026: «чтобы зрячей моделью получить всё на местах»).
                //
                // Слова, снятые движком на телефоне, — черновик: на почтовой наклейке разбор
                // поверх них менял местами отправителя с получателем, выдавал телефон за
                // номер накладной и сочинял «г. Лумброван». Но и выбрасывать страницу нельзя:
                // по её словам разбор исправляет цифру и гасит номер, не сошедшийся с
                // контрольной суммой. Модель, которая ВИДИТ кадр и при этом ссылается на
                // слова страницы, даёт и то, и другое.
                val eyes = input.state.kind == ObjectKind.IMAGE && llm.canHandle(input)
                reportStage(if (eyes) "Смотрю на снимок" else "Читаю страницу")
                val (answer, answeredBy) = ask(
                    input,
                    withBrief(input.metadata, understandPrompt(laidOut, index = index)),
                    eyes = eyes,
                )
                reportStage("Проверяю прочитанное по странице")
                val parsed = parseFieldCandidates(answer)
                // Сверять есть с чем всегда, когда Point читал сам: слой слов снимка или
                // окно текста объекта (#809, «нет в тексте — нет знания»).
                val readText = layer?.text?.takeIf { it.isNotBlank() } ?: window

                val (roles, roleDisputes) = roleReadings(answer, laidOut, layer)

                // Что с чем связано (#1176): страница держит колонку при её подписи, и
                // прочтение, стоящее в одном блоке с названной стороной, — про неё. Связь
                // известна до суда: судья выбирает значение среди прочтений, и чьё какое —
                // такая же улика, как форма и опора в словах страницы, а не правка поверх
                // готового значения в обход воронки.
                val belongings = layer?.belongings(parsed.fields, roles).orEmpty()
                val judged = judgeFields(parsed.fields, layer, readText, belongings)

                val retried = judged.retry.takeIf { it.isNotEmpty() }?.let { keys ->
                    reportStage("Контрольная цифра не сошлась — перечитываю")
                    val again = ask(input, retryPrompt(keys, laidOut, index), eyes = eyes).text

                    // Перечитанное — другие прочтения, и стороны у них свои (#1176): связь
                    // считается по тем прочтениям, которые судятся, а не по прошлым.
                    val more = parseFieldCandidates(again).fields.filterKeys { it in keys }
                    judgeFields(more, layer, readText, layer?.belongings(more, roles).orEmpty())
                }
                val fields = judged.won + retried?.won.orEmpty()

                val blocked = (judged.blocked.keys + retried?.blocked?.keys.orEmpty()).associateWith { key ->
                    (judged.blocked[key].orEmpty() + retried?.blocked?.get(key).orEmpty()).distinct()
                }

                // Курсор для следующего нажатия «Понять» — пока не дочитано, окно сдвигается
                // дальше; дочитано — курсор больше не нужен (#682/#683).
                val progress = if (fullyRead) {
                    emptyMap()
                } else {
                    mapOf(META_READ_CHARS to readSoFar.toString(), META_READ_TOTAL_CHARS to full.length.toString())
                }
                val message = if (fullyRead) null else partialReadMessage(readSoFar, full.length)

                if (fields.isEmpty() && parsed.single.isEmpty() && roles.isEmpty() && parsed.contacts.isEmpty()) {

                    // ADR-0001 §9: не дочитано — «недостаточно», не «не найдено».
                    // Дочитанное судится по всему накопленному — найденное раньше не
                    // гаснет; вопрос задан — след остаётся (#1176).
                    val state = if (fullyRead) {
                        investigationOutcome(input.metadata, cumulativeFactKeys(input.metadata))
                    } else {
                        InvestigationState.INSUFFICIENTLY_INVESTIGATED
                    }
                    val extra = progress + state.orEmptyInvestigation()
                    ActionResult.Done(
                        message ?: NOTHING_NEW,
                        extra.takeIf { it.isNotEmpty() }?.let { Findings(metadata = it) },
                    )
                } else {

                    val (values, anchors) =
                        structuredValues(input, fields.mapValues { it.value.text } + parsed.single + roles)
                    val merged = mergeFacts(input.metadata, values)

                    val roleAlts = roleDisputes
                        .filterKeys { key -> normConsensus(merged[key].orEmpty()) == normConsensus(roles[key].orEmpty()) }
                        .map { (key, readings) ->
                            key + META_ALT_SUFFIX to altValue((listOfNotNull(merged[key]) + readings).distinct())
                        }

                    val roleSources = roles.keys
                        .filter { key -> !merged[key].isNullOrBlank() }
                        .filter { key -> Provenance.MODEL > provenanceOf(merged, key) }
                        .map { key -> key + META_SOURCE_SUFFIX to Provenance.MODEL.wire }

                    // Принадлежность — аннотация ключа, как «кто прочитал» и «откуда»
                    // (#1176): значение факта не дублируется, названа лишь сторона, к
                    // которой оно относится. Хозяина называет то же решение судьи, что и
                    // значение, — сверки по тексту постфактум нет.
                    val owned = com.point.core.flow.belongingFacts(
                        merged,
                        fields.mapNotNull { (key, field) -> field.owner?.let { key to it } }.toMap(),
                    )

                    // Согласие исполнителей — улика и в текстовом пути (#1176): второй
                    // виток, увидевший то же значение, подтверждает его отметкой на
                    // свидетеля; суд продолжения идёт уже по подтверждённому знанию.
                    val named = addActor(
                        merged +
                            annotations(merged, fields, judgedByLayer = layer != null, blocked = blocked) +
                            roleAlts + roleSources + owned,
                        values.keys,
                        answeredBy,
                    )
                    val agreed = named + com.point.core.flow.agreementEvidence(named, values.keys)

                    // Спираль ведёт состояние всегда (#1176): без следа «Понять
                    // сильнее» не наступало после первого же полного витка.
                    val state = if (fullyRead) {
                        investigationOutcome(agreed, values.keys)
                    } else {
                        InvestigationState.INSUFFICIENTLY_INVESTIGATED
                    }

                    // «Понять» — знание о том же объекте, а не превращение (ADR-0001 §18):
                    // человек остаётся на исходнике, факты прирастают. Success здесь ронял
                    // его в дубль-объект со знаком вопроса.
                    // Телефон, принадлежащий человеку, — его контакт (#1176): пара
                    // «имя + номер» приходит связью, а не отдельным правилом под наклейку.
                    val owners = com.point.core.flow.personContacts(
                        belongings[META_ENTITY_PREFIX + "phone"].orEmpty(),
                        roles,
                    )

                    val people = contactNodes(input, (parsed.contacts + owners).distinct())

                    // Виток говорит, что прибавилось (#1176): знание, осевшее только в
                    // графе, для человека не случилось. Недочитанность важнее дельты —
                    // она зовёт дочитать.
                    ActionResult.Done(
                        message ?: com.point.core.flow.spiralDelta(input.metadata, agreed) ?: UNDERSTOOD,
                        Findings(
                            metadata = agreed + anchors + progress + state.orEmptyInvestigation(),
                            objects = people.objects,
                            relations = people.relations,
                        ),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось понять документ", recoverable = true) }
        }

    private suspend fun readWithEyes(input: PointObject): ActionResult {
        reportStage("Смотрю на снимок")
        val (answer, answeredBy) = ask(input, withBrief(input.metadata, VISUAL_PROMPT), eyes = true)
        val parsed = parseFieldCandidates(answer)
        val judged = judgeFields(parsed.fields, layer = null)
        val fields = judged.won
        if (fields.isEmpty() && parsed.single.isEmpty()) {
            return ActionResult.Failure("На снимке ничего не разобрать", recoverable = true)
        }
        val (roles, _) = roleReadings(answer, elements = emptyList(), layer = null)
        val (values, anchors) =
            structuredValues(input, fields.mapValues { it.value.text } + parsed.single + roles)
        val merged = mergeFacts(input.metadata, values)

        // Человек с телефоном — узел графа и здесь: иначе номер висел бы без хозяина,
        // ровно как в кейсе наклейки (#747, пункт 12).
        val people = contactNodes(input, parsed.contacts)
        // Порядок — суть спирали (#1176): имена исполнителей → согласие уликами → суд;
        // без следа состояния «Понять сильнее» у снимка не наступало.
        val grown = run {
            val noted = addActor(
                merged + doubts(merged, parsed.unsure) +
                    annotations(merged, fields, judgedByLayer = false, blocked = judged.blocked),
                values.keys,
                answeredBy,
            )
            val agreed = noted + com.point.core.flow.agreementEvidence(noted, values.keys)
            agreed + anchors +
                investigationOutcome(agreed, values.keys).orEmptyInvestigation() +
                (META_READING_MODE to ReadingMode.HANDWRITTEN.name)
        }
        return ActionResult.Done(
            com.point.core.flow.spiralDelta(input.metadata, grown) ?: UNDERSTOOD,
            Findings(
                metadata = grown,
                objects = people.objects,
                relations = people.relations,
            ),
        )
    }

    /**
     * Ответ модели вместе с именем сервиса, который его дал (#1127).
     *
     * Кто именно отвечал, знает только цепочка сервисов: она перебирает их по очереди и
     * возвращает первый живой ответ. Без этого имени знание из облака стояло в графе
     * безымянным, и сравнить двух исполнителей одного вопроса было нечем.
     */
    private data class Answer(val text: String, val by: String)

    /** [eyes] — отдать модели сам снимок; иначе уходит только текст объекта. */
    private suspend fun ask(input: PointObject, prompt: String, eyes: Boolean = false): Answer {

        // Виток «сильнее» идёт другой моделью (#1010): кто уже понимал этот объект, знает
        // аннотация сводки — цепочка постарается взять свежего исполнителя, а одиночный
        // клиент честно ответит собой.
        val answered = com.point.core.flow.actorsOf(input.metadata, com.point.core.flow.META_SEMANTIC_SUMMARY).toSet()
        val result = llm.run(if (eyes) input else textOnly(input), prompt, answered)
        return Answer(File(result.uri.value).readText(), result.metadata[META_ANSWERED_BY].orEmpty())
    }

    private fun annotations(
        merged: Map<String, String>,
        fields: Map<String, JudgedField>,
        judgedByLayer: Boolean,
        blocked: Map<String, List<String>>,
    ): Map<String, String> = buildMap {
        fields.forEach { (key, field) ->
            val readings = (listOfNotNull(merged[key]) + alternativesOf(merged, key) + field.candidates).distinct()
            if (com.point.core.flow.isMultiValueFact(key)) {

                // #652: второй телефон — «ещё один», а не спор первого. Прежний спор
                // этого ключа тоже переезжает в «ещё» и гаснет.
                val primary = normConsensus(merged[key].orEmpty())
                val more = (com.point.core.flow.moreOf(merged, key) + readings)
                    .distinctBy { normConsensus(it) }
                    .filterNot { normConsensus(it) == primary }
                if (more.isNotEmpty()) put(key + com.point.core.flow.META_MORE_SUFFIX, altValue(more))
                if (merged[key + META_ALT_SUFFIX] != null) put(key + META_ALT_SUFFIX, altValue(emptyList()))
            } else if (readings.size > 1) put(key + META_ALT_SUFFIX, altValue(readings))

            if (normConsensus(merged[key].orEmpty()) != normConsensus(field.text)) return@forEach
            if (judgedByLayer) {
                val existing = merged[key + META_EVIDENCE_SUFFIX]?.split(',')?.count { it.isNotBlank() } ?: 0
                if (field.evidence.size >= existing) {
                    put(key + META_EVIDENCE_SUFFIX, field.evidence.joinToString(",") { it.name.lowercase() })
                }
            }
            val source = if (field.grounded) Provenance.OCR else Provenance.MODEL
            if (source > provenanceOf(merged, key)) put(key + META_SOURCE_SUFFIX, source.wire)

            // Обрезанная до даты фраза не исчезает — она остаётся подписью значения (#782).
            field.line?.let { put(key + com.point.core.flow.META_LINE_SUFFIX, it) }
        }
        blocked.forEach { (key, texts) ->
            if (texts.isNotEmpty()) put(key + META_BLOCKED_SUFFIX, altValue(texts))
        }
    }

    private fun textOnly(input: PointObject) =
        if (input.state.kind == ObjectKind.TEXT) input
        else input.copy(mime = "text/plain", metadata = input.metadata - META_OCR_TEXT_REF)

    private fun atomLayer(input: PointObject): AtomLayer? =
        input.metadata[META_OCR_ATOMS_REF]?.let { ref ->
            runCatching { AtomCodec.decode(File(ref).readText()) }.getOrNull()
        }

    /** `null` — состояние сейчас не пересматривается, а не «сбросить на не исследовано». */
    private fun InvestigationState?.orEmptyInvestigation(): Map<String, String> =
        this?.let { withInvestigation(emptyMap(), UnderstandCapability.ID, it) }.orEmpty()

    /**
     * Все накопленные ключи знания без пересчитываемых ссылок и курсора чтения — курсор
     * («сколько уже прочитано») и ссылка на слой OCR не факты о содержимом и не обязаны
     * считаться находкой при итоговом суде.
     */
    private fun cumulativeFactKeys(metadata: Map<String, String>): Set<String> =
        metadata.keys - com.point.core.flow.REFRESHABLE_KNOWLEDGE

    private companion object {

        // Решение владельца (#682/#683) «брать за раз больше текста»: было 6 000 —
        // втрое-вчетверо разумнее для одного разбора, но не «убрать предел вовсе»,
        // квота бесплатных сервисов конечна.
        const val MAX_CHARS = 24_000

        const val NOTHING_NEW = "Point уже прочитал всё, что здесь есть"

        const val UNDERSTOOD = "Стало понятнее"

        val VISUAL_PROMPT =
            "Прочитай, что написано на снимке. Это может быть табло счётчика, рукописная " +
                "запись, фото документа под углом или бликом. " +
                "Отвечай ТОЛЬКО строками вида KEY=значение, по одной на строку. Разрешённые KEY: " +
                "METER (показание счётчика — ТОЛЬКО цифры показания, без единицы измерения), " +
                "TRACK (номер отправления), PHONE, EMAIL, URL, ADDRESS, DATE, CARD, " +
                "GEO (координаты), PLACE (куда ехать: название места дословно), " +
                "AMOUNT (сумма — ТОЛЬКО цифры, без валюты), RECEIPT (номер квитанции или чека). " +
                // Зрячее чтение спрашивало меньше текстового, и «Понять» на снимке
                // отдавало меньше знания, чем «Понять» на тексте того же снимка (#770,
                // живая охота 11.08.2026): телефон приходил без хозяина, роли не приходили
                // вовсе. Одно действие — один состав знания, кто бы его ни исполнил.
                "Если рядом с номером телефона стоит имя его владельца, вместо PHONE дай " +
                "строку CONTACT=<номер> | <имя полностью>, по одной на каждого человека. " +
                "Отдельно назови, кто играет роли: sender (отправитель), receiver " +
                "(получатель), carrier (перевозчик), issuer (кто выдал документ), — строками " +
                "вида роль=имя. Ролей может не быть ни одной: визитка, вывеска, меню, " +
                "фотография — не документ с отправителем. Ни одной роли лучше, чем натянутая. " +
                "Добавь строку SUMMARY=<что на снимке, 3-6 слов>. " +
                // Языковое правило общее с текстовым и голосовым путём (#1036, история —
                // при самом правиле); оговорка про надпись на снимке — своя, зрячая (#670).
                answerLanguageRule("SUMMARY и любые словесные значения", "надпись на снимке") + " " +
                "Цифры читай ровно так, как видишь: не додумывай и не выравнивай под привычный " +
                "формат. Если цифра не видна — не пиши строку вовсе. " +
                // Сомнение — часть ответа, а не повод молчать (#670).
                "Если какое-то значение разобрал неуверенно, добавь строку UNSURE=<KEY через " +
                "запятую>. " +
                "Если разобрать нечего — ответь ровно NONE."

        fun retryPrompt(keys: Set<String>, elements: List<LayoutElement>, index: String?): String {
            val names = keys.mapNotNull { key ->
                UNDERSTAND_CONTRACT_KEYS.entries.firstOrNull { META_ENTITY_PREFIX + it.value == key }?.key
            }
            return understandPrompt(elements, index = index) +
                "\nКандидаты полей ${names.joinToString(", ")} не прошли проверку контрольной цифры. " +
                "Перечитай страницу и верни ТОЛЬКО эти поля заново, до $MAX_FIELD_CANDIDATES " +
                "кандидатов каждое, с метками слов. Если настоящего значения нет — не пиши строку.\n"
        }
    }
}

/**
 * Слово человека не участвует в машинном премерже: модельное чтение не смеет ни вытеснить,
 * ни «отремонтировать» подтверждённый человеком факт (ADR-0001 §8) — оно просто не претендует.
 */
internal fun withoutHumanFacts(values: Map<String, String>, known: Map<String, String>): Map<String, String> =
    values.filterKeys { provenanceOf(known, it) != Provenance.HUMAN }

/**
 * Подписанные контакты (#653): каждая пара «имя+номер» от модели — узел «Человек»
 * с телефоном внутри. Имя — заголовочный факт узла, номер — его знание: «Позвонить»
 * и «Сохранить контакт» на узле работают от этого номера.
 */
internal fun contactNodes(source: PointObject, contacts: List<com.point.core.flow.PersonContact>): Findings {
    if (contacts.isEmpty()) return Findings()
    val objects = LinkedHashMap<String, PointObject>()
    val relations = mutableListOf<Relation>()
    contacts.forEach { contact ->

        // Идентичность стороны одна на всех (#1176, partyNodeId): «НОВІК» из роли
        // отправителя и «НОВІК» из пары — один человек, а не два узла.
        val id = partyNodeId(source.id, contact.name)
        objects.getOrPut(id) {
            PointObject(
                id = id,
                mime = "text/plain",
                uri = ValueRef(contact.name),
                state = ObjectState(KIND_PERSON, setOf(Feature.HAS_PHONE)),
                metadata = linkedMapOf(
                    META_GRAPH_ROLE_PREFIX + "contact" to contact.name,
                    META_GRAPH_ROLE_PREFIX + "contact" + META_SOURCE_SUFFIX to Provenance.MODEL.wire,
                    META_ENTITY_PREFIX + "phone" to contact.phone,
                    META_ENTITY_PREFIX + "phone" + META_SOURCE_SUFFIX to Provenance.MODEL.wire,
                ),
                provenance = Provenance.MODEL,
                sourceObjects = listOf(source.id),
                creatorAction = "understand",
            )
        }
        relations += Relation(id, RelationType.FOUND_IN, source.id)
    }
    return Findings(objects = objects.values.toList(), relations = relations.toList())
}

internal fun roleReadings(
    answer: String,
    elements: List<LayoutElement>,
    layer: AtomLayer?,
): Pair<Map<String, String>, Map<String, List<String>>> {
    val fromElements = parseClassification(answer, elements)
        .associate { META_GRAPH_ROLE_PREFIX + it.role.key to it.element.text }
        .filterValues(::plausiblePersonName)
    if (layer == null) return fromElements to emptyMap()

    val byKey = CLASSIFIER_ROLES.associateBy { it.key }
    val values = LinkedHashMap<String, String>()
    val disputes = LinkedHashMap<String, List<String>>()
    answer.lineSequence().forEach { raw ->
        val line = raw.trim()
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val role = byKey[line.substring(0, eq).trim().lowercase()] ?: return@forEach
        val metaKey = META_GRAPH_ROLE_PREFIX + role.key
        if (metaKey in values) return@forEach
        val candidate = splitCandidate(line.substring(eq + 1).trim()) ?: return@forEach
        if (candidate.ids.isEmpty()) return@forEach

        val idsByAtom = layer.atoms.associateBy { it.id }
        val pointed = candidate.ids.map(::bareIndexId)
        val withoutLabel = pointed.filterNot { id ->
            idsByAtom[id]?.text?.let { role.isRoleLabel(it) } == true
        }
        val resolved = layer.resolve(AtomAddress.ByIds(withoutLabel.ifEmpty { pointed }))
        if (resolved.atoms.isEmpty()) return@forEach
        val page = resolved.text
        val model = candidate.text
        val chosen = when {
            normConsensus(model) == normConsensus(page) -> page
            isRepairOf(page, model) -> model
            else -> {
                if (plausiblePersonName(page)) disputes[metaKey] = listOf(page, model)
                page
            }
        }
        if (!plausiblePersonName(chosen)) {
            disputes.remove(metaKey)
            return@forEach
        }
        values[metaKey] = chosen
    }

    fromElements.forEach { (key, text) -> values.putIfAbsent(key, text) }
    return values to disputes
}

internal data class JudgedField(
    val text: String,
    val evidence: Set<EvidenceClass>,
    val grounded: Boolean,
    val candidates: List<String>,

    /** Строка документа вокруг значения — подпись при нём, а не оно само (#782). */
    val line: String? = null,

    /** Чьё это значение (#1176): сторона и то самое прочтение, которое ею стало. */
    val owner: com.point.core.flow.PartyReading? = null,
)

internal data class JudgedFields(
    val won: Map<String, JudgedField>,
    val retry: Set<String>,

    val blocked: Map<String, List<String>> = emptyMap(),
)

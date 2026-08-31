package com.point.data

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.readerFailure
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.Findings
import com.point.core.flow.stripStatusBar
import com.point.core.flow.KIND_IDENTIFIER
import com.point.core.flow.documentType
import com.point.core.flow.META_SEMANTIC_TYPE
import com.point.core.flow.KIND_ADDRESS
import com.point.core.flow.KIND_DATE
import com.point.core.flow.KIND_EMAIL
import com.point.core.flow.KIND_PHONE
import com.point.core.flow.KIND_URL
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.AtomRecognizer
import com.point.core.flow.EntityExtractor
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.INCOMPLETE_TIMEOUT
import com.point.core.flow.META_READ_PREPARED
import com.point.core.flow.META_READ_UPSCALE
import com.point.core.flow.META_READING_MODE
import com.point.core.flow.META_UNUSABLE_REASON
import com.point.core.flow.OCR_READ_BUDGET_MS
import com.point.core.flow.OcrClock
import com.point.core.flow.PaperWhitener
import com.point.core.flow.ReadingBudget
import com.point.core.flow.READ_PREPARED_STRAIGHTENED
import com.point.core.flow.READ_PREPARED_WHITENED
import com.point.core.flow.inSourceFrame
import com.point.core.flow.onSourceFrame
import com.point.core.flow.readerFailureIsFatal
import com.point.core.flow.readingModeOf
import com.point.core.flow.ObjectStore
import com.point.core.flow.StraightFrame
import com.point.core.flow.amountFacts
import com.point.core.flow.betterReading
import com.point.core.flow.geoFacts
import com.point.core.flow.poorlyRead
import com.point.core.flow.meterFacts
import com.point.core.flow.receiptFacts
import com.point.core.flow.trackFacts
import com.point.core.flow.by
import com.point.core.flow.readConfidently
import com.point.core.flow.locate
import com.point.core.flow.regionWire
import com.point.core.flow.META_AT_REGION
import com.point.core.flow.entityObjects
import com.point.core.flow.entityDelta
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class OcrInvestigation @Inject constructor() : Capability {

    override val id = ID

    override val icon = ""

    override val meta = CapabilityMeta(
        investigation = true,
        latency = Latency.SLOW,
        mayYield = setOf(
            Feature.HAS_TEXT, Feature.HAS_PHONE, Feature.HAS_EMAIL, Feature.HAS_ADDRESS,
            Feature.HAS_DATE, Feature.HAS_CARD, Feature.HAS_URL, Feature.HAS_WORD_LAYER,
        ),
        mayYieldKinds = setOf(KIND_PHONE, KIND_EMAIL, KIND_URL, KIND_ADDRESS, KIND_DATE, KIND_IDENTIFIER),
    )

    override fun label(state: ObjectState) = "Распознаю текст…"

    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE

    override fun produces(state: ObjectState) = state

    companion object {

        // Не «ocr»: этот id носит пользовательское действие «Распознать текст». Резолвер
        // группирует реализаторы по id, и общий id подсовывал циклу знания реализатор
        // действия — знание превращалось в «вернуло объект вместо знания».
        val ID = com.point.core.flow.KnownCapabilities.IMAGE_TEXT
    }
}

class OcrInvestigationRealizer @Inject constructor(
    private val store: ObjectStore,
    private val recognizer: AtomRecognizer,
    private val extractor: EntityExtractor,

    // Плохое чтение — повод подготовить кадр и прочитать снова (#1041, #1046). Две ступени
    // одного захода: ровный свет слова не двигает, выпрямление двигает.
    private val straight: StraightFrame,
    private val whitener: PaperWhitener,

    // Часы захода (#861, #1046): предел чтения один на все его ступени, а не по пределу на
    // ступень. Системное время — за швом, иначе проверить это можно было бы только ожиданием.
    private val clock: OcrClock,
) : Realizer {

    private val crops = FocusCrop(store)

    /**
     * Объект, каким его увидит читатель: та же вещь, но вырезанная по показанной области и
     * увеличенная. Focus не подменяет объект — он адресует его часть (ADR-0001 §10), поэтому
     * знание вернётся к исходнику, а вырезка исчезнет вместе со scratch.
     */
    private suspend fun focused(obj: PointObject): PointObject? {
        val region = com.point.core.flow.focusOf(obj.metadata, obj.id)?.region ?: return null
        val cut = runCatching { crops.of(obj.uri.value, region) }.getOrNull() ?: return null
        return obj.copy(uri = com.point.core.model.ScratchRef(cut.absolutePath))
    }

    override val capabilityId = OcrInvestigation.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        com.point.core.flow.investigated { findings(input) }

    private suspend fun findings(obj: PointObject): Findings = withContext(Dispatchers.IO) {

        // Часы у захода одни на все его ступени (#861, #1046). Читатель отмеряет свой предел
        // каждому чтению заново, поэтому цепочка «прочитать → подготовить → прочитать снова»
        // сама по себе множит предел на число ступеней, а тап человека ждёт под потолком
        // действия в десять минут (`ACTION_CEILING_MS`) — и вместо ответа он получил бы
        // «не уложилось». Предел, который человек чувствует, обязан остаться одним числом.
        val budget = ReadingBudget(OCR_READ_BUDGET_MS, clock)

        // Человек показал область — читаем её, а не страницу целиком (#426). Мелкое на большом
        // кадре иначе не читается вовсе: вопрос «что на странице» неправильный, когда нужное
        // занимает считанные проценты кадра.
        val frame = focused(obj) ?: obj
        val onFrame = recognizer.read(frame)

        // Ридер честно сигналит, что не смог посмотреть (decode failed / not an image /
        // таймаут). Пусто И оборвано = не «текста нет», а «не смогли посмотреть» (ADR-0001 §9).
        // Частичное чтение с атомами остаётся частичным знанием.
        val broken = onFrame.incomplete
        if (broken != null && onFrame.atoms.isEmpty() && onFrame.text.isBlank()) {
            // Человеку — свои слова, чужое «decode failed» остаётся в журнале (#686).
            val reason = readerFailure(broken, obj.state.kind)

            // Годность — часть состояния объекта (#684/#685): когда дело в самом объекте
            // (испорчен, не изображение вовсе), это не провал операции, а знание — оно
            // остаётся с объектом и закрывает путь наружу, а не гаснет после этого тапа.
            // Долгое чтение или слишком большой снимок — про попытку сейчас, не про объект,
            // и по-прежнему уходят как неудача операции (ADR-0001 §9, §18).
            if (readerFailureIsFatal(broken)) {
                return@withContext Findings(
                    features = setOf(Feature.UNUSABLE),
                    metadata = mapOf(META_UNUSABLE_REASON to reason),
                )
            }
            com.point.core.flow.ownWords(reason)
        }

        // Плохое чтение — не тупик, а повод подготовить кадр и прочитать снова (#1041, #1046).
        // Снятый под углом счёт кашей и оставался, а фото акта с тенью от руки не разбиралось
        // вовсе: и выпрямить, и выбелить кадр человек должен был догадаться сам, нажав «Скан»,
        // а знание после этого ложилось на родившуюся картинку, а не на снимок, которым он
        // поделился. Ровный кадр за это не платит: второго захода у него нет.
        val again = if (worthSecondRead(onFrame, budget)) secondRead(frame, budget) else null

        // Знанием становится лучшее из двух чтений, а не последнее: второй заход бывает
        // беднее первого, когда границей листа стал чек внутри кадра (#1041).
        val layer = again?.let { betterReading(onFrame, it.layer) } ?: onFrame

        // Второй заход победил — тогда с ним приходят и его слова, и имя его кадра.
        val won = again?.takeIf { layer !== onFrame }

        // Слой слов берётся у того кадра, чья геометрия — геометрия снимка (#1013, #1046). У
        // выбеленной копии она такая сразу: свет по листу выровняли, слова не двигали. У
        // выпрямленной — своя, и слова приезжают на снимок по углам страницы, из которых
        // копия родилась (#1332). Слоя не остаётся только там, где обратного хода нет вовсе;
        // слова проигравшего чтения не берутся никогда — они спорили бы с текстом объекта
        // (#1041).
        val words = if (won == null) onFrame else won.words
        val atomsRef = if (words != null && words.atoms.isNotEmpty()) {
            store.newScratchFile("atoms.tsv").also { File(it.value).writeText(AtomCodec.encode(words)) }
        } else {
            null
        }

        val mode = layer.takeIf { it.incomplete == null }?.let { readingModeOf(it) }

        val zoom = layer.transform?.upscale?.takeIf { it > 1 }?.toString()
        val evidenceOnly = Findings(

            features = if (atomsRef != null) setOf(Feature.HAS_WORD_LAYER) else emptySet(),
            metadata = buildMap {
                atomsRef?.let { put(META_OCR_ATOMS_REF, it.value) }
                mode?.let { put(META_READING_MODE, it.name) }
                zoom?.let { put(META_READ_UPSCALE, it) }

                // Пометки о подготовке кадра здесь не бывает: сюда приходит только чтение,
                // признанное плохим, а второй заход годным чтением и становится (#1041, #1046).
            },
        )
        val raw = layer.text

        // Неудачное чтение не должно объявляться знанием: вопрос «что написано на снимке»
        // закрылся бы навсегда, а на бессмыслице дальше строились бы действия (#694). Сюда
        // приходит и кадр, который не дался и выпрямленным: такой вопрос остаётся открытым
        // (#988, #1041), а не закрывается находкой.
        if (poorlyRead(raw, layer)) return@withContext evidenceOnly

        val text = stripStatusBar(raw)

        // Прочитанное с кадра называет себя чтением (#990): путь знания — не путь объекта.
        val entities = entityDelta(
            obj,
            extractor.extract(text.take(com.point.core.flow.INVESTIGATION_TEXT_CHARS)),
            text.take(com.point.core.flow.INVESTIGATION_TEXT_CHARS),
            com.point.core.model.Provenance.OCR,
        )

        val trackMeta = trackFacts(text.take(com.point.core.flow.INVESTIGATION_TEXT_CHARS)) +
            com.point.core.flow.serialFacts(text.take(com.point.core.flow.INVESTIGATION_TEXT_CHARS))
        val (identifiers, idRelations) = identifierObjects(obj, text.take(com.point.core.flow.INVESTIGATION_TEXT_CHARS), trackMeta)
        val url = URL_REGEX.find(text)?.value
        val ref = store.newScratchFile("txt")
        File(ref.value).writeText(text)
        Findings(
            features = entities.features + Feature.HAS_TEXT +
                (if (atomsRef != null) setOf(Feature.HAS_WORD_LAYER) else emptySet()) +
                if (url != null) setOf(Feature.HAS_URL) else emptySet(),
            metadata = buildMap {
                putAll(entities.metadata)
                if (url != null) putIfAbsent(META_ENTITY_PREFIX + "url", url)

                documentType(text)?.let { putIfAbsent(META_SEMANTIC_TYPE, it) }

                putAll(trackMeta)

                putAll(meterFacts(text.take(com.point.core.flow.INVESTIGATION_TEXT_CHARS)))
                putAll(geoFacts(text.take(com.point.core.flow.INVESTIGATION_TEXT_CHARS)))

                putAll(amountFacts(text.take(com.point.core.flow.INVESTIGATION_TEXT_CHARS)))
                putAll(receiptFacts(text.take(com.point.core.flow.INVESTIGATION_TEXT_CHARS)))
                mode?.let { put(META_READING_MODE, it.name) }
                zoom?.let { put(META_READ_UPSCALE, it) }
                won?.let { put(META_READ_PREPARED, it.prep) }
                put(META_OCR_TEXT_REF, ref.value)
                atomsRef?.let { put(META_OCR_ATOMS_REF, it.value) }

                // Сомнение чтения — сомнение значения (#1109). Ридер знает про каждое слово,
                // насколько он в нём уверен, и это знание кончалось на слое: цифра, прочитанная
                // плохо, приходила к человеку такой же спокойной, как прочитанная чисто.
                // Своей системы уверенности здесь не заводится — используется та же улика
                // значения, какой уже пользуются «Понять» и экран: у сомнительного значения
                // улик нет, и оно показывается как «возможно».
                putAll(doubted(this, layer))
            },

            // Узел найденного несёт то же сомнение, что и факт: человек входит в значение
            // и обязан видеть там ровно то, что видел в списке (#1109). Место ищется по тем
            // словам, которые стоят там, где смотрит человек (#1013): по словам снимка или
            // по перенесённым на снимок словам выбеленной копии. После выпрямления таких слов
            // нет — уликой остаётся сырое чтение, а найденное со второго захода остаётся без
            // места, пока `enhance` не отдаёт своё преобразование (#1332).
            objects = locate(entities.objects + identifiers, words ?: onFrame)
                .map { node -> node.copy(metadata = node.metadata + doubted(node.metadata, layer)) },
            relations = entities.relations + idRelations,

            // Кто именно прочитал этот кадр (#1127): у чтения нет одного исполнителя — читает
            // тот движок, которого выбрала цепочка, и по имени видно, чьё это прочтение,
            // когда второй путь прочтёт то же место иначе.
        ).by(readBy(layer))
    }

    /**
     * Стоит ли плохое чтение второго захода (#1041, #1046).
     *
     * Платит только кадр, на котором движок вообще что-то увидел. «Текст был, но не дался»
     * отличимо от «текста нет» единственным сигналом — пустотой чтения, — и без этого условия
     * за подготовку кадра и второе полное чтение платил бы каждый снимок без текста: кот,
     * селфи, кадр видео, то есть самый частый объект в Point.
     *
     * И платит только дочитанный кадр. Оборванное чтение — не плохое чтение, а недочитанное
     * (ADR-0001 §9): судить о нём как о плохом нечем, а второй заход стоит подготовки кадра и
     * ещё одного полного чтения — вдвое больше того срока, в который уже не уложились.
     *
     * И только пока у захода есть часы: [budget] один на все ступени, и кадр, чьё первое
     * чтение съело весь предел, второго не получает, даже если движок дочитал до конца сам.
     */
    private fun worthSecondRead(onFrame: AtomLayer, budget: ReadingBudget): Boolean {
        val sawSomething = onFrame.atoms.isNotEmpty() || onFrame.text.isNotBlank()
        val readToTheEnd = onFrame.incomplete == null
        return sawSomething &&
            readToTheEnd &&
            poorlyRead(onFrame.text, onFrame) &&
            budget.leftMs() > 0
    }

    /**
     * Второй заход по подготовленному кадру (#1041, #1046).
     *
     * Ступени идут от бережной к решительной, и порядок здесь — часть поведения. Сперва ровный
     * свет: он не двигает слова, поэтому прочитанное возвращается на снимок вместе с
     * координатами и по нему по-прежнему можно подсветить найденное и вырезать ячейку. Не
     * помогло — снимается перспектива. Своя геометрия у выпрямленной копии не отменяет места:
     * копия родилась из четырёхугольника страницы, и слова возвращаются на снимок тем же
     * ходом в обратную сторону (#1332). Обратного хода нет только у страницы, расправленной
     * по линиям разлиновки: тогда углов не существует, и слов у этого чтения не остаётся —
     * молчание честнее места, показанного мимо строки.
     *
     * Тот же читатель и тот же кадр — новых объектов из этого не рождается: обе копии лежат в
     * scratch и уходят вместе с ним, как вырезка по показанной области.
     *
     * Ступень отдаёт только годное чтение: если и подготовленный кадр прочитался кашей, второе
     * прочтение не лучше первого и вопрос обязан остаться открытым (#988) — иначе Point
     * объявил бы находкой то же самое, от чего и уходил. Годное — ещё не значит лучшее: какое
     * из двух чтений станет знанием, решает [betterReading], а не порядок заходов.
     */
    private suspend fun secondRead(frame: PointObject, budget: ReadingBudget): SecondRead? {
        val white = runCatching { whitener.whitened(frame.uri.value) }.getOrNull()
        val onWhite = white?.let { readCopy(frame, it.path, budget) }
        if (white != null && onWhite != null && !poorlyRead(onWhite.text, onWhite)) {
            val onSource = onWhite.inSourceFrame(white.shrink)
            return SecondRead(onSource, words = onSource, prep = READ_PREPARED_WHITENED)
        }

        // Ровный свет не помог. Дальше идут только те, у кого остались часы: упёршееся в
        // предел чтение выбеленной копии выпрямления уже не получает, и не получает его кадр,
        // на который у захода не осталось времени. Иначе выпрямление считалось бы даром — его
        // копию всё равно некому было бы прочитать.
        if (onWhite?.incomplete == INCOMPLETE_TIMEOUT || budget.leftMs() <= 0) return null

        val copy = runCatching { straight.of(frame.uri.value) }.getOrNull() ?: return null
        val onStraight = readCopy(frame, copy.path, budget)?.takeUnless { poorlyRead(it.text, it) } ?: return null
        val words = copy.page?.let { onStraight.onSourceFrame(it, copy.width, copy.height) }
        return SecondRead(onStraight, words = words, prep = READ_PREPARED_STRAIGHTENED)
    }

    /**
     * Тот же читатель по копии кадра — пока у захода есть часы (#861, #1046).
     *
     * Читатель отмеряет свой предел каждому чтению заново и о предыдущих ступенях не знает.
     * Здесь про них знают: исчерпан предел захода — следующего чтения нет, и ответ у человека
     * остаётся тем, каким был, а не приезжает втрое позже.
     */
    private suspend fun readCopy(frame: PointObject, path: String, budget: ReadingBudget): AtomLayer? {
        if (budget.leftMs() <= 0) return null
        return runCatching {
            recognizer.read(frame.copy(uri = com.point.core.model.ScratchRef(path)))
        }.getOrNull()
    }

    /**
     * Значения, которые слой прочитал неуверенно.
     *
     * Пустая улика — существующий способ сказать «возможно» (`isAssumption`): у значения нет
     * подтверждающих улик, и оно остаётся значением, а не исчезает. Уже посчитанные улики не
     * трогаются: там о значении знают больше, чем уверенность отдельных слов.
     */
    private fun doubted(facts: Map<String, String>, layer: AtomLayer): Map<String, String> {
        if (layer.atoms.isEmpty()) return emptyMap()
        return facts.keys
            .filter { it.startsWith(META_ENTITY_PREFIX) }
            .filterNot { com.point.core.flow.isAnnotationKey(it) || com.point.core.flow.isStateKey(it) }
            .filterNot { facts.containsKey(it + com.point.core.flow.META_EVIDENCE_SUFFIX) }
            .filter { key -> layer.readConfidently(facts.getValue(key)) == false }
            .associate { it + com.point.core.flow.META_EVIDENCE_SUFFIX to "" }
    }

    /** Имена движков, чьими глазами прочитан этот кадр. */
    private fun readBy(layer: AtomLayer): String =
        com.point.core.flow.actorValue(layer.atoms.map { it.reader }.filter { it.isNotBlank() })

    /**
     * Локализация найденного на источнике (`at.region`) — там, где слой атомов уже есть
     * и значение находится на странице однозначно. Два объекта одного kind после этого
     * различимы не только идентичностью, но и местом (ADR-0001 §2).
     */
    private fun locate(objects: List<PointObject>, layer: AtomLayer): List<PointObject> {
        if (layer.atoms.isEmpty()) return objects
        return objects.map { obj ->
            if (META_AT_REGION in obj.metadata) return@map obj
            val box = layer.locate(obj.uri.value) ?: return@map obj
            obj.copy(metadata = obj.metadata + (META_AT_REGION to regionWire(box)))
        }
    }

    private companion object {
        val URL_REGEX = Regex("""https?://\S+""")
    }
}

/**
 * Чтение со второго захода и то, годятся ли его слова снимку (#1041, #1046).
 *
 * [words] — слой в координатах снимка человека, и он есть только там, где кадр готовили без
 * правки геометрии. Иначе слоя слов у объекта не остаётся вовсе: подсветка найденного и
 * вырезка ячейки считают по снимку, чужие координаты встали бы мимо строки (#1013, #1332), а
 * слова проигравшего чтения спорили бы с текстом объекта (#1041).
 *
 * [prep] — каким кадром добыт текст: улика происхождения знания, а не слово для человека.
 */
private class SecondRead(val layer: AtomLayer, val words: AtomLayer?, val prep: String)


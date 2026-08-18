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
import com.point.core.flow.META_READ_UPSCALE
import com.point.core.flow.META_READING_MODE
import com.point.core.flow.META_UNUSABLE_REASON
import com.point.core.flow.readerFailureIsFatal
import com.point.core.flow.readingModeOf
import com.point.core.flow.ObjectStore
import com.point.core.flow.amountFacts
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
        val ID = com.point.core.model.CapabilityId("image-text")
    }
}

class OcrInvestigationRealizer @Inject constructor(
    private val store: ObjectStore,
    private val recognizer: AtomRecognizer,
    private val extractor: EntityExtractor,
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

        // Человек показал область — читаем её, а не страницу целиком (#426). Мелкое на большом
        // кадре иначе не читается вовсе: вопрос «что на странице» неправильный, когда нужное
        // занимает считанные проценты кадра.
        val layer = recognizer.read(focused(obj) ?: obj)

        // Ридер честно сигналит, что не смог посмотреть (decode failed / not an image /
        // таймаут). Пусто И оборвано = не «текста нет», а «не смогли посмотреть» (ADR-0001 §9).
        // Частичное чтение с атомами остаётся частичным знанием.
        val broken = layer.incomplete
        if (broken != null && layer.atoms.isEmpty() && layer.text.isBlank()) {
            // Человеку — свои слова, чужое «decode failed» остаётся в журнале (#686).
            val reason = readerFailure(broken)

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
            error(reason)
        }

        val atomsRef = if (layer.atoms.isNotEmpty()) {
            store.newScratchFile("atoms.tsv").also { File(it.value).writeText(AtomCodec.encode(layer)) }
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
            },
        )
        val raw = layer.text

        // Неудачное чтение не должно объявляться знанием: вопрос «что написано на снимке»
        // закрылся бы навсегда, а на бессмыслице дальше строились бы действия (#694).
        if (poorlyRead(raw, layer)) return@withContext evidenceOnly

        val text = stripStatusBar(raw)

        // Прочитанное с кадра называет себя чтением (#990): путь знания — не путь объекта.
        val entities = entityDelta(
            obj,
            extractor.extract(text.take(com.point.core.flow.INVESTIGATION_TEXT_CHARS)),
            text.take(com.point.core.flow.INVESTIGATION_TEXT_CHARS),
            com.point.core.model.Provenance.OCR,
        )

        val trackMeta = trackFacts(text.take(com.point.core.flow.INVESTIGATION_TEXT_CHARS))
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
            // и обязан видеть там ровно то, что видел в списке (#1109).
            objects = locate(entities.objects + identifiers, layer)
                .map { node -> node.copy(metadata = node.metadata + doubted(node.metadata, layer)) },
            relations = entities.relations + idRelations,

            // Кто именно прочитал этот кадр (#1127): у чтения нет одного исполнителя — читает
            // тот движок, которого выбрала цепочка, и по имени видно, чьё это прочтение,
            // когда второй путь прочтёт то же место иначе.
        ).by(readBy(layer))
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


package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
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
import com.point.core.flow.META_READING_MODE
import com.point.core.flow.readingModeOf
import com.point.core.flow.ObjectStore
import com.point.core.flow.geoFacts
import com.point.core.flow.weaklyRead
import com.point.core.flow.meterFacts
import com.point.core.flow.trackFacts
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * The screenshot-comes-alive step (#64): a shared IMAGE is OCR'd **in the background**
 * (on-device Tesseract — never the cloud, never a prompt) and the entities found in the
 * recognised text light up straight on the image — «Позвонить»/«Создать событие» appear
 * on a screenshot without the manual «Распознать текст» hop.
 *
 * The recognised text is written to scratch once and referenced via [META_OCR_TEXT_REF],
 * so entity realizers and the OCR capability itself reuse it instead of re-running the
 * engine. Gibberish (photos of the world, not of text) is discarded by the shared
 * [looksLikeOcrGarbage] heuristic — a photo of food simply stays a photo.
 */
class OcrEnricher @Inject constructor(
    private val store: ObjectStore,
    private val recognizer: AtomRecognizer,
    private val extractor: EntityExtractor,
) : Enricher {

    override val meta = EnricherMeta(
        cost = EnrichCost.SLOW,
        mayYield = setOf(
            Feature.HAS_TEXT, Feature.HAS_PHONE, Feature.HAS_EMAIL, Feature.HAS_ADDRESS,
            Feature.HAS_DATE, Feature.HAS_CARD, Feature.HAS_URL,
        ),
        // The gate must know OCR can yield objects, not only actions: on a parcel screenshot
        // the address and the deadline are the whole point, and neither opens a new button.
        mayYieldKinds = setOf(KIND_PHONE, KIND_EMAIL, KIND_URL, KIND_ADDRESS, KIND_DATE, KIND_IDENTIFIER),
        label = "Распознаю текст…",
    )

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.IMAGE

    override suspend fun enrich(obj: PointObject): EnrichmentDelta = withContext(Dispatchers.IO) {
        // Сбой движка — не рукопись: режим чтения пишется только когда движок отработал,
        // иначе мы приписали бы объекту происхождение, которого не наблюдали (#263).
        val read = runCatching { recognizer.read(obj) }.getOrNull()
        val layer = read ?: AtomLayer(emptyList())
        // Слой — улика, а не представление (#257): он персистится ДО любых гейтов. Гейт мусора
        // ниже судит, что показывать человеку; уничтожать прочитанное он права не имеет —
        // на фото монитора с настоящим текстом он ошибается (кадры 07/21 корпуса #262).
        val atomsRef = if (layer.atoms.isNotEmpty()) {
            store.newScratchFile("atoms.tsv").also { File(it.value).writeText(AtomCodec.encode(layer)) }
        } else {
            null
        }
        // Режим чтения — улика, а не показ (#263): он пишется ДО гейта мусора, потому что
        // именно закрытый гейт и означает «слов не собрали, читать будет зрячая модель».
        val mode = read?.let { readingModeOf(it) }
        val evidenceOnly = EnrichmentDelta(
            metadata = buildMap {
                atomsRef?.let { put(META_OCR_ATOMS_REF, it.value) }
                mode?.let { put(META_READING_MODE, it.name) }
            },
        )
        val raw = layer.text
        // Судит слой, а не текст: движок сам говорит, читал он или угадывал. Прежний гейт мерил
        // долю букв — и прятал от человека начисто прочитанную таблицу договоров только потому,
        // что она состоит из номеров, дат и сумм (замер примеров 02.08.2026).
        if (raw.isBlank() || weaklyRead(layer)) return@withContext evidenceOnly
        // #233: the phone's own clock sits at the top of every screenshot and used to become
        // «дата 15:12». Dropped once, here, so no later reader can mistake furniture for content.
        val text = stripStatusBar(raw)

        val entities = entityDelta(obj, extractor.extract(text.take(MAX_CHARS)), text.take(MAX_CHARS))
        // The waybill number is the whole reason the user shared the screenshot. It reaches the
        // graph on this path — the TEXT-only enricher never runs on what was actually shared.
        // Один суд правила на весь вызов (#264): факты и узлы говорят одно и то же о
        // происхождении и уликах, потому что берут их из одной карты, а не считают дважды.
        val trackMeta = trackFacts(text.take(MAX_CHARS))
        val (identifiers, idRelations) = identifierObjects(obj, text.take(MAX_CHARS), trackMeta)
        val url = URL_REGEX.find(text)?.value
        val ref = store.newScratchFile("txt")
        File(ref.value).writeText(text)
        EnrichmentDelta(
            features = entities.features + Feature.HAS_TEXT +
                if (url != null) setOf(Feature.HAS_URL) else emptySet(),
            metadata = buildMap {
                putAll(entities.metadata)
                if (url != null) putIfAbsent(META_ENTITY_PREFIX + "url", url)
                // «Посылка», а не «Изображение» (#222, шаг 5): скриншот приходит сюда, не в
                // DocumentTypeEnricher — тот работает по TEXT, а у картинки текста ещё нет.
                documentType(text)?.let { putIfAbsent(META_SEMANTIC_TYPE, it) }
                // Трек — и факт, а не только узел графа (#260): готовность «Отследить
                // отправление» считается по метаданным, и правило — её бесплатный источник.
                putAll(trackMeta)
                // Показание счётчика и координаты (#262) — те же офлайновые правила формы.
                // Именно этот путь и есть настоящий: человек делится ФОТО счётчика и скрином
                // карты, а не текстовым файлом; TEXT-энричер на них не запускается никогда —
                // ровно тот урок, на котором однажды уже потеряли трек (см. identifierObjects).
                putAll(meterFacts(text.take(MAX_CHARS)))
                putAll(geoFacts(text.take(MAX_CHARS)))
                mode?.let { put(META_READING_MODE, it.name) }
                put(META_OCR_TEXT_REF, ref.value)
                atomsRef?.let { put(META_OCR_ATOMS_REF, it.value) }
            },
            // The screenshot's own findings become graph objects (#222) — the branch address
            // read off a parcel screenshot is a place, not a line in a checklist.
            objects = entities.objects + identifiers,
            relations = entities.relations + idRelations,
        )
    }

    private companion object {
        const val MAX_CHARS = 20_000
        val URL_REGEX = Regex("""https?://\S+""")
    }
}

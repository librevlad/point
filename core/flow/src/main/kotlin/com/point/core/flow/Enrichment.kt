package com.point.core.flow

import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.PointObject
import com.point.core.model.Relation
import kotlinx.coroutines.flow.Flow

/**
 * Порт цикла Progressive Understanding (RFC §11).
 *
 * Механизм под ним — обычный: Capability → Resolver → Realizer. Отдельного контракта
 * исследования не существует (ADR-0001 §11).
 */
interface Enrichment {
    fun enrich(obj: PointObject): Flow<EnrichmentUpdate>
}

data class EnrichmentUpdate(
    val features: Set<Feature>,
    val metadata: Map<String, String>,
    val running: List<String>,
    val objects: List<PointObject> = emptyList(),
    val relations: List<Relation> = emptyList(),

    /**
     * Состояние операции, а не знания: исследования, которые сорвались (ADR-0001 §9, §18).
     * Провал не переводит знание в `NOT_FOUND` и не стирает найденное раньше.
     */
    val failed: List<FailedInvestigation> = emptyList(),

    /**
     * Тоже состояние операции: шаг не закончился, а ждёт человека (ADR-0001 §18).
     * Ожидание не является исходом и знанием не становится.
     */
    val awaiting: List<AwaitingInvestigation> = emptyList(),
)

data class FailedInvestigation(
    val id: CapabilityId,
    val label: String?,
    val reason: String,
)

data class AwaitingInvestigation(
    val id: CapabilityId,
    val label: String?,
    val prompt: String,

    val needsImage: Boolean = false,
)

const val META_OCR_TEXT_REF = "ocr.text.ref"

/**
 * Прочитанный текст **значением**, а не ссылкой на файл устройства (#811).
 *
 * `ocr.text.ref` указывает на scratch-файл того телефона, где читали, и при переносе на
 * компьютер ведёт в никуда: снимок приезжал «непрочитанным», и человеку предлагали
 * распознать его заново. Знание, живущее ссылкой на локальный файл, не переносимо по
 * построению — поэтому на ту сторону едет само содержимое.
 */
const val META_READ_TEXT = "read.text"

/**
 * Кем объект был там, откуда приехал (ADR-0001 §20).
 *
 * «На той стороне это тот же объект, а не новый». Перенос заводил новую вещь с новым
 * именем в списке, и вернувшийся с компьютера объект вставал рядом со своим исходником
 * как посторонний. Идентичность едет вместе с ним и знанием об объекте не является:
 * это адрес узла, а не факт о вещи.
 */
const val META_ORIGIN_ID = "origin.id"

/** Длиннее в дорогу не берём: это уже документ, а не знание о нём. */
const val READ_TEXT_TRAVEL_LIMIT = 100_000

/** Ссылка, по которой лежит прочитанный текст этого устройства, — её и надо прочитать в дорогу. */
fun textRefForTravel(meta: Map<String, String>): String? =
    meta[META_OCR_TEXT_REF]?.takeIf { it.isNotBlank() }

/**
 * Знание собирается в дорогу (#811, #995).
 *
 * Ссылка на прочитанный текст — путь в scratch этого устройства, и на той стороне она ведёт
 * в никуда: объект приезжал «непрочитанным», и там первым делом предлагали прочитать его
 * заново. Поэтому едет само содержимое, а ссылка не едет вовсе — даже когда прочитать файл
 * не вышло: мёртвый путь только притворяется знанием.
 *
 * [text] — то, что лежит по ссылке; `null`, если прочитать не удалось.
 */
fun knowledgePackedForTravel(meta: Map<String, String>, text: String?): Map<String, String> {
    if (textRefForTravel(meta) == null) return meta
    val carried = text?.takeIf { it.isNotBlank() } ?: return meta - META_OCR_TEXT_REF
    return meta - META_OCR_TEXT_REF + (META_READ_TEXT to carried.take(READ_TEXT_TRAVEL_LIMIT))
}

/** Текст, приехавший значением: его надо положить файлом этого устройства (#811). */
fun textArrivedFromTravel(meta: Map<String, String>): String? =
    meta[META_READ_TEXT]?.takeIf { it.isNotBlank() }

/**
 * Знание приехало и снова становится знанием этой стороны (#811, #995).
 *
 * [ref] — куда приехавший текст лёг здесь, или `null`, если положить не вышло. Признак
 * «текст есть» ставится тут, а не едет отдельным полем протокола: он следует из того, что
 * текст лёг. Без этого перенос терял понятое — та сторона звала делать уже сделанное.
 */
fun knowledgeArrivedFromTravel(meta: Map<String, String>, ref: String?): com.point.core.model.Findings {
    if (textArrivedFromTravel(meta) == null) return com.point.core.model.Findings(metadata = meta)
    val kept = ref?.takeIf { it.isNotBlank() }
        ?: return com.point.core.model.Findings(metadata = meta - META_READ_TEXT)
    return com.point.core.model.Findings(
        features = setOf(Feature.HAS_TEXT),
        metadata = meta - META_READ_TEXT + (META_OCR_TEXT_REF to kept),
    )
}

const val META_OCR_ATOMS_REF = "ocr.atoms.ref"

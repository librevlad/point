package com.point.core.flow

import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Relation

/**
 * Focus — пользовательское указание, какую часть объекта смотреть (ADR-0001 §10).
 *
 * Focus не является объектом и не создаёт объект. Он адресует часть уже существующего.
 */
data class Focus(
    val objectId: String,

    val region: Box? = null,

    val atomIds: List<String> = emptyList(),

    val text: String? = null,

    /**
     * Части показанной области, когда человек обвёл не одно место, а несколько (#549).
     *
     * [region] остаётся их объединением: тому, кому важна только «где смотреть», ничего
     * знать про части не нужно. Части нужны там, где каждая обведённая штука обрабатывается
     * сама по себе, — например когда их замазывают.
     */
    val parts: List<Box> = emptyList(),
) {
    /** Показанные места: части, если человек обвёл несколько, иначе — сама область. */
    val places: List<Box> get() = parts.ifEmpty { listOfNotNull(region) }
}

/**
 * Graph State — то, из чего принимаются оба решения: что предложить человеку и что исследовать
 * дальше (RFC §4 — Graph + Context + Focus + Investigation State).
 *
 * Это не новый примитив, а вход, собранный из уже существующих: объект с его знанием,
 * найденные объекты, отношения, Focus и Intent.
 */
data class GraphState(
    val obj: PointObject,

    val found: List<PointObject> = emptyList(),

    val relations: List<Relation> = emptyList(),

    val focus: Focus? = null,

    val intent: Intent? = null,
) {
    val state: ObjectState get() = obj.state

    val facts: Map<String, String> get() = obj.metadata

    fun fact(key: String): String? = obj.metadata[key]?.takeIf { it.isNotBlank() }

    fun investigation(id: CapabilityId): InvestigationState = investigationStateOf(obj.metadata, id)

    fun foundOf(kind: ObjectKind): List<PointObject> = found.filter { it.state.kind == kind }

    fun relatedTo(id: String): List<Relation> = relations.filter { it.fromId == id || it.toId == id }

    fun investigations(): Map<CapabilityId, InvestigationState> = obj.metadata
        .filterKeys(::isStateKey)
        .entries
        .associate { (key, _) ->
            val id = CapabilityId(key.removePrefix(META_INVESTIGATED_PREFIX))
            id to investigationStateOf(obj.metadata, id)
        }

    /** Вопросы, которые уже задавали и на которые знание так и не получено. */
    fun openQuestions(): List<CapabilityId> =
        investigations().filterValues { it != InvestigationState.FOUND }.keys.toList()
}

const val META_FOCUS_REGION = "focus.region"

const val META_FOCUS_IDS = "focus.ids"

/** Части показанной области — «l t r b» через `;` (#549). */
const val META_FOCUS_PARTS = "focus.parts"

/**
 * Локализация найденного объекта на его источнике — где именно он там находится.
 *
 * Обычная metadata-конвенция (как `sel.*` и `focus.*`), а не новый тип: `at.region` —
 * "left top right bottom" в координатах исходного изображения, `at.ids` — атомы слоя.
 * Именно этим два объекта одного kind на одном фото различимы не только по id.
 */
const val META_AT_REGION = "at.region"

const val META_AT_IDS = "at.ids"

fun regionWire(box: Box): String = "${box.left} ${box.top} ${box.right} ${box.bottom}"

fun regionOfWire(wire: String?): Box? = wire?.split(' ')
    ?.mapNotNull(String::toFloatOrNull)
    ?.takeIf { it.size == 4 }
    ?.let { Box(it[0], it[1], it[2], it[3]) }

/**
 * Focus едет к исполнителю тем же путём, что и остальное знание об объекте, — как часть
 * состояния самого объекта. Объект при этом остаётся тем же: Focus его не подменяет.
 */
fun withFocus(metadata: Map<String, String>, focus: Focus?): Map<String, String> {
    if (focus == null) return metadata
    return metadata +
        listOfNotNull(
            focus.region?.let { META_FOCUS_REGION to regionWire(it) },
            focus.atomIds.takeIf { it.isNotEmpty() }?.let { META_FOCUS_IDS to it.joinToString(" ") },
            focus.parts.takeIf { it.isNotEmpty() }?.let { META_FOCUS_PARTS to partsWire(it) },
        )
}

fun focusOf(metadata: Map<String, String>, objectId: String): Focus? {
    val region = regionOfWire(metadata[META_FOCUS_REGION])
    val ids = metadata[META_FOCUS_IDS]?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
    val parts = partsOfWire(metadata[META_FOCUS_PARTS])
    return if (region == null && ids.isEmpty()) null else Focus(objectId, region, ids, parts = parts)
}

fun partsWire(parts: List<Box>): String = parts.joinToString(";", transform = ::regionWire)

fun partsOfWire(wire: String?): List<Box> =
    wire?.split(';')?.mapNotNull { regionOfWire(it.trim()) }.orEmpty()

/**
 * Смысл, который сейчас уместен для объекта (Конституция §6, ADR-0001 §14).
 *
 * Выводится из состояния, а не из формы результата отдельной Capability: пока об объекте
 * ещё есть что узнать — уместно понимание; когда Focus указан, понимание тем более уместно.
 * Дальше порядок остаётся за политикой: Intent влияет на порядок и не убирает действия.
 */
fun leadingIntent(graph: GraphState, working: Boolean = false): Intent? = when {
    graph.focus != null -> Intent.UNDERSTAND
    working -> Intent.UNDERSTAND
    graph.openQuestions().isNotEmpty() -> Intent.UNDERSTAND
    else -> null
}

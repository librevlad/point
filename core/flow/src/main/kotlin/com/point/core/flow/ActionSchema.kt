package com.point.core.flow

import com.point.core.model.Bubble
import com.point.core.model.CapabilityId

data class ActionSchema(

    val id: String,

    val label: String,
    val fields: List<FieldSpec>,

    val runs: CapabilityId? = null,
)

data class FieldSpec(
    val key: String,

    val label: String,

    val critical: Boolean = false,

    val anchor: Boolean = critical,

    val insteadOf: String? = null,
)

private val FieldSpec.requirement: String get() = insteadOf ?: key

sealed interface Readiness {
    data class Ready(val present: List<FieldReading>) : Readiness
    data class Missing(val missing: List<FieldSpec>, val present: List<FieldReading>) : Readiness
}

data class FieldReading(
    val spec: FieldSpec,
    val value: String,
    val alternatives: List<String> = emptyList(),

    /** «Ещё значения» того же вида — другие объекты, а не спор прочтений одного. */
    val extras: List<String> = emptyList(),

    val assumption: Boolean = false,

    val hint: String? = null,
)

fun ActionSchema.readiness(facts: Map<String, String>): Readiness {
    val present = fields.mapNotNull { spec ->
        facts[spec.key]?.takeIf { it.isNotBlank() }?.let { value ->

            // Равенство прочтений меряется той же нормализацией, что и merge:
            // буквальное сравнение рождало «или: 26.04.2026 26.04.2026». Спор (.alt)
            // и «ещё значения» (.more) — разные вещи: второй телефон — не конфликт.
            // Даты равны по календарному дню: «26.04.2026 20:04» — не ещё одна дата.
            fun readingKey(text: String) =
                (if (spec.key.endsWith("date")) humanDayOf(text)?.toString() else null)
                    ?: normConsensus(text)
            fun distinctReadings(raw: List<String>) = raw
                .distinctBy { readingKey(it) }
                .filter { readingKey(it) != readingKey(value) }
            val alternatives = distinctReadings(alternativesOf(facts, spec.key))

            // Одно значение не бывает сразу и спором, и «ещё»: списки «или/ещё»
            // с одинаковыми номерами дублировали друг друга (#652, кейс 24).
            val claimed = alternatives.map { readingKey(it) }.toSet()
            FieldReading(
                spec, value,
                alternatives = alternatives,
                extras = distinctReadings(moreOf(facts, spec.key))
                    .filterNot { readingKey(it) in claimed },
                assumption = isAssumption(facts, spec.key),
                hint = fieldHint(spec.key, value),
            )
        }
    }

    val satisfied = present.map { it.spec.requirement }.toSet()
    val missingCritical = fields.filter { it.critical && it.requirement !in satisfied }
        .groupBy { it.requirement }
        .map { (_, specs) ->

            specs.first().let { primary ->

                if (specs.size == 1) {
                    primary
                } else {
                    val labels = specs.map { it.label }
                    primary.copy(label = labels.dropLast(1).joinToString(", ") + " или " + labels.last())
                }
            }
        }
    return if (missingCritical.isEmpty()) {
        Readiness.Ready(present)
    } else {
        Readiness.Missing(missingCritical, present)
    }
}

data class ActionReadiness(val schema: ActionSchema, val readiness: Readiness)

fun ActionReadiness.runner(bubbles: List<Bubble>): Bubble? {
    if (readiness !is Readiness.Ready) return null
    val id = schema.runs ?: return null
    return bubbles.firstOrNull { it.capabilityId == id }
}

fun ActionReadiness.shownField(): FieldReading? =
    (readiness as? Readiness.Ready)?.present?.firstOrNull { it.spec.critical }

fun readinessShownFacts(
    facts: Map<String, String>,
    schemas: List<ActionSchema> = ACTION_SCHEMAS,
): Map<String, String> = buildMap {
    actionReadiness(facts, schemas).forEach { row ->
        row.shownField()?.let { field -> put(field.spec.key, field.value) }
    }
}

fun actionReadiness(
    facts: Map<String, String>,
    schemas: List<ActionSchema> = ACTION_SCHEMAS,
): List<ActionReadiness> = schemas.mapNotNull { schema ->
    val readiness = schema.readiness(facts)
    val present = when (readiness) {
        is Readiness.Ready -> readiness.present
        is Readiness.Missing -> readiness.present
    }
    if (present.any { it.spec.anchor }) ActionReadiness(schema, readiness) else null
}

val ACTION_SCHEMAS: List<ActionSchema> = listOf(
    // Строка без действия называет ЗНАНИЕ существительным, а не обещает глаголом
    // (#671, слова владельца: «не вижу смысла в "передать показания счетчика".
    // куда передать? как передать?»; «у нас и так нет функции отслеживания»).
    ActionSchema(
        id = "track-parcel",
        label = "Номер отправления",

        runs = null,
        fields = listOf(
            FieldSpec(META_ENTITY_TRACK, "трек-номер", critical = true),

            FieldSpec(META_GRAPH_ROLE_PREFIX + "carrier", "перевозчик", anchor = true),

            FieldSpec(META_ENTITY_PREFIX + "date", "дата"),
        ),
    ),
    ActionSchema(
        id = "save-contact",
        label = "Сохранить контакт",

        runs = CapabilityId("save-contact"),
        fields = listOf(
            FieldSpec(META_ENTITY_PREFIX + "phone", "телефон", critical = true),

            FieldSpec(META_ENTITY_PREFIX + "email", "почта", anchor = true),

            FieldSpec(META_ENTITY_PREFIX + "address", "адрес"),
        ),
    ),

    ActionSchema(
        id = "route",
        label = "Построить маршрут",

        runs = CapabilityId("map"),
        fields = listOf(
            FieldSpec(META_ENTITY_PREFIX + "address", "адрес", critical = true, anchor = false),
            FieldSpec(META_ENTITY_GEO, "координаты", critical = true, insteadOf = META_ENTITY_PREFIX + "address"),

            FieldSpec(
                META_ENTITY_PLACE, "место", critical = true,
                insteadOf = META_ENTITY_PREFIX + "address", anchor = true,
            ),
        ),
    ),

    ActionSchema(
        id = "meter-reading",
        label = "Показание счётчика",

        runs = null,
        fields = listOf(
            FieldSpec(META_ENTITY_METER, "показание", critical = true),

            FieldSpec(META_ENTITY_METER_UNIT, "единица"),

            FieldSpec(META_ENTITY_PREFIX + "date", "дата"),
        ),
    ),

    ActionSchema(
        id = "pay-by-requisites",
        label = "Реквизиты перевода",

        runs = null,
        fields = listOf(
            FieldSpec(META_ENTITY_PREFIX + "card", "карта", critical = true),
            FieldSpec(META_ENTITY_AMOUNT, "сумма", critical = true, anchor = false),

            FieldSpec(META_GRAPH_ROLE_PREFIX + "receiver", "получатель"),
            FieldSpec(META_ENTITY_AMOUNT_CURRENCY, "валюта"),
            FieldSpec(META_ENTITY_PREFIX + "date", "дата"),
        ),
    ),

    ActionSchema(
        id = "forward-receipt",
        label = "Переслать квитанцию",

        // Пересылка квитанции — это поделиться самим объектом: дверь настоящая,
        // а не глагол без действия (живой прогон 2026-08-09).
        runs = CapabilityId("share"),
        fields = listOf(
            FieldSpec(META_ENTITY_RECEIPT, "номер квитанции", critical = true),
            FieldSpec(
                META_ENTITY_PREFIX + "url", "ссылка на квитанцию",
                critical = true, insteadOf = META_ENTITY_RECEIPT, anchor = false,
            ),

            FieldSpec(META_ENTITY_AMOUNT, "сумма"),
            FieldSpec(META_ENTITY_PREFIX + "date", "дата"),
        ),
    ),

    ActionSchema(
        id = "reply",
        label = "Ответить",

        runs = CapabilityId("email"),
        fields = listOf(
            FieldSpec(META_ENTITY_PREFIX + "email", "адрес почты", critical = true, anchor = false),
            FieldSpec(META_ENTITY_SUBJECT, "тема", anchor = true),

            FieldSpec(META_GRAPH_ROLE_PREFIX + "sender", "отправитель"),
            FieldSpec(META_ENTITY_PREFIX + "date", "дата"),
        ),
    ),
)

// «Мне в буфере целиковые блоки не нужны» (владелец, 2026-08-09): копия строки
// действия кладёт одно ключевое значение — то же, что строка показывает, — а не
// склейку всех полей с подписями.
fun copyableValue(readiness: Readiness): String? {
    val present = when (readiness) {
        is Readiness.Ready -> readiness.present
        is Readiness.Missing -> readiness.present
    }.filter { it.value.isNotBlank() }
    return (present.firstOrNull { it.spec.critical } ?: present.singleOrNull())?.value
}

/**
 * «Или/ещё» под строкой действия — только про собственное (critical) значение
 * действия. Споры вспомогательных полей (дата, валюта) живут на своих узлах и не
 * повторяются под каждым действием: на чеке один спор даты печатался трижды —
 * «это непонятно и неюзабельно» (владелец, 2026-08-09). Знание не прячется (P8):
 * узел поля показывает спор полностью.
 */
fun ownDisputes(present: List<FieldReading>): List<FieldReading> =
    present.filter { it.spec.critical && it.alternatives.isNotEmpty() }

fun ownExtras(present: List<FieldReading>): List<FieldReading> =
    present.filter { it.spec.critical && it.extras.isNotEmpty() }

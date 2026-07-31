package com.point.core.flow

/**
 * Схема принадлежит **действию**, а не типу документа (#260, design v3 §5–6).
 *
 * Тип документа как корневой роутер — ошибка, названная тремя архитекторами консилиума
 * независимо: входы гибридные (фото посылки с чеком, скрин переписки со вложенным треком),
 * одна метка заставляет систему саму отрезать половину информации, а «заполнено 6 из 9»
 * становится красивой ложью — документ не понят, его насильно засунули в чужой контракт.
 *
 * Поэтому здесь нет «схемы накладной». Есть схема действия «Отследить отправление», и полнота
 * считается по нему: у действия есть **критические** поля, без которых оно не работает, и
 * побочные, которые весят почти ноль («имя отправителя» для отслеживания). Готовность бинарна —
 * действие готово или нет; число заполненных полей не сообщается вовсе, потому что это метрика,
 * которую легко накрутить и невозможно честно истолковать.
 *
 * Схема **не запускает** ничего сама: первый экран (≤300 мс) показывает возможные действия,
 * заполнение начинается после явного тапа (`Object → Intent → Capability`), а эта модель лишь
 * говорит, чего не хватает, — строкой «не хватает только X», а не формой из девяти полей.
 */
data class ActionSchema(
    /** Устойчивый идентификатор схемы — для тестов, журнала и будущего маппинга на capability. */
    val id: String,
    /** Название действия человеку: «Отследить отправление». */
    val label: String,
    val fields: List<FieldSpec>,
)

/**
 * Поле схемы: какой факт объекта действие читает и обязано ли оно им обладать.
 *
 * [key] — ключ метаданных объекта (`entity.track`, `graph.role.carrier`): схема читает те же
 * факты, что пишут энричеры и «Понять», а не заводит собственный мир значений.
 */
data class FieldSpec(
    val key: String,
    /** Название поля человеку, именительный падеж: «трек-номер». */
    val label: String,
    /** Критическое: без него действие не работает. Некритическое отсутствие готовности не рушит. */
    val critical: Boolean = false,
)

/**
 * Готовность действия по фактам объекта: бинарная, по критическим полям.
 *
 * [Missing.missing] — только критические: «не хватает только X» обязано называть то, без чего
 * действие не работает, а не всё незаполненное — иначе это та же форма из девяти полей.
 */
sealed interface Readiness {
    data class Ready(val present: List<FieldReading>) : Readiness
    data class Missing(val missing: List<FieldSpec>, val present: List<FieldReading>) : Readiness
}

/**
 * Прочитанное поле: значение и альтернативные чтения, если источники разошлись
 * (`<key>.alt`, [alternativesOf]). Спор не прячется за готовностью: действие может быть
 * готово по спорному значению, и человек обязан видеть, что оно спорное.
 */
data class FieldReading(
    val spec: FieldSpec,
    val value: String,
    val alternatives: List<String> = emptyList(),
)

/** Готовность одной схемы по фактам объекта. Чистая функция — UI и тесты зовут её напрямую. */
fun ActionSchema.readiness(facts: Map<String, String>): Readiness {
    val present = fields.mapNotNull { spec ->
        facts[spec.key]?.takeIf { it.isNotBlank() }?.let { value ->
            FieldReading(spec, value, alternativesOf(facts, spec.key).filter { it != value })
        }
    }
    val presentKeys = present.map { it.spec.key }.toSet()
    val missingCritical = fields.filter { it.critical && it.key !in presentKeys }
    return if (missingCritical.isEmpty()) {
        Readiness.Ready(present)
    } else {
        Readiness.Missing(missingCritical, present)
    }
}

/** Схема и её готовность — строка секции готовности на экране объекта. */
data class ActionReadiness(val schema: ActionSchema, val readiness: Readiness)

/**
 * Готовность известных действий по фактам объекта — только тех, к которым документ имеет
 * отношение: схема видна, когда хотя бы одно её поле прочитано. Пустой документ не получает
 * список «не хватает всего» — это был бы тот же опросник из девяти полей, только с минусами.
 */
fun actionReadiness(
    facts: Map<String, String>,
    schemas: List<ActionSchema> = ACTION_SCHEMAS,
): List<ActionReadiness> = schemas.mapNotNull { schema ->
    val readiness = schema.readiness(facts)
    val visible = when (readiness) {
        is Readiness.Ready -> readiness.present.isNotEmpty()
        is Readiness.Missing -> readiness.present.isNotEmpty()
    }
    if (visible) ActionReadiness(schema, readiness) else null
}

/**
 * Первые действия со схемой. «Извлечь позиции» здесь сознательно нет: у объекта пока не бывает
 * честного факта «в документе есть таблица» — сигнал появится вместе с кандидатами и уликами
 * (#261) либо корпусом (#262), а схема, чью готовность нечем посчитать, лгала бы в обе стороны.
 */
val ACTION_SCHEMAS: List<ActionSchema> = listOf(
    ActionSchema(
        id = "track-parcel",
        label = "Отследить отправление",
        fields = listOf(
            FieldSpec(META_ENTITY_TRACK, "трек-номер", critical = true),
            FieldSpec(META_GRAPH_ROLE_PREFIX + "carrier", "перевозчик"),
            FieldSpec(META_ENTITY_PREFIX + "date", "дата"),
        ),
    ),
    ActionSchema(
        id = "save-contact",
        label = "Сохранить контакт",
        fields = listOf(
            FieldSpec(META_ENTITY_PREFIX + "phone", "телефон", critical = true),
            FieldSpec(META_ENTITY_PREFIX + "email", "почта"),
            FieldSpec(META_ENTITY_PREFIX + "address", "адрес"),
        ),
    ),
)

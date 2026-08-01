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
    /**
     * Якорное: наличие этого поля означает, что документ **про это действие**. Дата и адрес
     * есть почти на каждом скриншоте (таймстемп переписки — тоже `entity.date`, #244), и
     * гейт видимости по «хоть одному полю» звал «Отследить отправление» на любой чат
     * (ревью #260). Про посылку говорят трек и перевозчик — универсальные поля читаются
     * схемой, но карточку не зовут.
     */
    val anchor: Boolean = critical,
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
    /**
     * Предположение (#261, design v3 §4): улики считались (`<key>.ev`) и независимых классов
     * меньше [CONFIRMED_CLASSES] — «одно доказательство — предположение, и оно видно как
     * предположение». Улики не считались вовсе (ключа нет) — поле не судили, и врать про него
     * маркером нельзя ни в одну сторону.
     *
     * Считается общим [isAssumption] (#264): то же самое слово «возможно» стоит под найденным
     * объектом, и две реализации одного суда разъехались бы на первой правке.
     */
    val assumption: Boolean = false,
)

/** Готовность одной схемы по фактам объекта. Чистая функция — UI и тесты зовут её напрямую. */
fun ActionSchema.readiness(facts: Map<String, String>): Readiness {
    val present = fields.mapNotNull { spec ->
        facts[spec.key]?.takeIf { it.isNotBlank() }?.let { value ->
            // Спор о чтении (.alt) и другие значения того же типа на странице (.more) для
            // человека — один вопрос: «а не то ли это?» — и показываются одной строкой «или:».
            val readings = (alternativesOf(facts, spec.key) + moreOf(facts, spec.key))
                .distinct().filter { it != value }
            FieldReading(spec, value, readings, assumption = isAssumption(facts, spec.key))
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
 * отношение: схема видна, когда прочитано хотя бы одно её **якорное** поле ([FieldSpec.anchor]).
 * Пустой документ не получает список «не хватает всего», а чат с таймстемпом «18:24» — карточку
 * про посылку (ревью #260): универсальное поле схему не зовёт, оно только читается ею.
 */
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
            // Перевозчик — якорь: роль «carrier» на странице значит «это про отправление».
            FieldSpec(META_GRAPH_ROLE_PREFIX + "carrier", "перевозчик", anchor = true),
            // Дата — универсал (таймстемп чата — тоже дата, #244): читается, но не зовёт.
            FieldSpec(META_ENTITY_PREFIX + "date", "дата"),
        ),
    ),
    ActionSchema(
        id = "save-contact",
        label = "Сохранить контакт",
        fields = listOf(
            FieldSpec(META_ENTITY_PREFIX + "phone", "телефон", critical = true),
            // Почта — якорь: адрес почты сам по себе контакт.
            FieldSpec(META_ENTITY_PREFIX + "email", "почта", anchor = true),
            // Адрес — универсал: адрес на счёте — место, а не «сохраните контакт».
            FieldSpec(META_ENTITY_PREFIX + "address", "адрес"),
        ),
    ),
)

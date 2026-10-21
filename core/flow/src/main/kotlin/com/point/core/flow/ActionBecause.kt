package com.point.core.flow

import com.point.core.model.Because
import com.point.core.model.Feature

import com.point.core.model.tr
/**
 * Какое найденное значение продолжает действие — его «потому что».
 *
 * Действия на экране объекта были разложены по виду функции: «Сделать», «Извлечь»,
 * «Превратить», «Отправить». Человек так не думает. Он видит визитку и думает «вот телефон —
 * позвонить», «вот адрес — где это», «а саму визитку — сохранить». Вещь → её смыслы →
 * естественные следующие шаги.
 *
 * Правило одно на любое действие и любой объект, без списка «какое действие к какому
 * значению»: действие продолжает найденное, если на этом объекте оно предлагается, а на той
 * же вещи без найденных значений — нет. Причина — первое значение, которого действию хватает
 * одного. Действие, которому хватает самой вещи, продолжает вещь (`null`); действие, которому
 * мало любого значения по отдельности («Собрать данные» — нужно хотя бы два), — тоже вещь:
 * оно о ней целиком.
 *
 * Снимаются только признаки значений, само знание остаётся: применимость, которая смотрит
 * на факты целиком («Исправить ошибки» — было бы что исправлять), от признака не зависит и
 * остаётся при вещи, а не прилипает к первому попавшемуся номеру.
 *
 * Найденный объект — сам значение (номер, адрес, человек): его действия продолжают его же,
 * причины вне его нет.
 */
fun becauseOf(
    capability: Capability,
    graph: GraphState,
    region: String = PhoneNumbers.DEFAULT_REGION,
): Because? {
    if (graph.state.kind in EXTRACTED_KINDS) return null
    val present = VALUE_SOURCES.filter { graph.state.has(it.feature) }
    if (present.isEmpty()) return null

    val bare = graph.state.features - VALUE_FEATURES
    fun acceptsWith(features: Set<Feature>): Boolean = runCatching {
        capability.accepts(graph.copy(obj = graph.obj.copy(state = graph.state.copy(features = features))))
    }.getOrDefault(false)

    if (acceptsWith(bare)) return null
    return present.firstOrNull { acceptsWith(bare + it.feature) }?.because(graph.facts, region)
}

/**
 * Найденное, которое бывает причиной действия, — в порядке, в котором причина называется,
 * когда действию хватает любого из них.
 *
 * Смысл вещи целиком — встреча, покупка, рецепт, вакансия — идёт первым: «Создать событие»
 * у приглашения продолжает встречу, а не одну из дат в нём. Дальше — значения, которыми
 * пользуются: номер, почта, адрес, место, ссылка, код, карта, срок, момент съёмки.
 *
 * Текст сюда не входит: прочитанное — это содержимое самой вещи, а не значение в ней, и
 * «Перевести» остаётся при вещи. Визитка-файл тоже: `.vcf` — это и есть контакт.
 */
private val VALUE_SOURCES: List<ValueSource> = listOf(
    Meaning(Feature.IS_MEETING, tr("Встреча")),
    Meaning(Feature.IS_PURCHASE, tr("Покупка")),
    Meaning(Feature.IS_RECIPE, tr("Рецепт")),
    Meaning(Feature.IS_JOB, tr("Вакансия")),
    Value(Feature.HAS_PHONE, META_ENTITY_PHONE),
    Value(Feature.HAS_EMAIL, META_ENTITY_PREFIX + "email"),
    Value(Feature.HAS_ADDRESS, META_ENTITY_ADDRESS),
    Value(Feature.HAS_GEO, META_ENTITY_GEO),
    Value(Feature.HAS_URL, META_ENTITY_URL),
    Value(Feature.HAS_QR, META_ENTITY_PREFIX + "qr"),
    Value(Feature.HAS_BARCODE, META_ENTITY_PREFIX + "barcode", name = tr("Штрихкод")),
    Value(Feature.HAS_CARD, META_ENTITY_PREFIX + "card"),
    Value(Feature.HAS_DATE, META_ENTITY_PREFIX + "date"),
    Value(Feature.HAS_SHOT_AT, META_SHOT_AT, name = tr("Снято")),
)

private val VALUE_FEATURES: Set<Feature> = VALUE_SOURCES.map { it.feature }.toSet()

private sealed interface ValueSource {
    val feature: Feature
    fun because(facts: Map<String, String>, region: String): Because
}

/** Смысл вещи целиком: название — вид смысла, значение — суть, если модель её сказала. */
private data class Meaning(override val feature: Feature, val name: String) : ValueSource {
    override fun because(facts: Map<String, String>, region: String) = Because(
        key = META_SEMANTIC_TYPE,
        name = name,
        value = facts[META_SEMANTIC_SUMMARY]?.takeIf { it.isNotBlank() },
    )
}

/** Значение в вещи: название — как его зовёт строка знания, значение — как его видно там же. */
private data class Value(
    override val feature: Feature,
    val key: String,
    val name: String? = null,
) : ValueSource {
    override fun because(facts: Map<String, String>, region: String) = Because(
        key = key,
        name = name ?: understoodName(key) ?: key.removePrefix(META_ENTITY_PREFIX),
        value = facts[key]?.takeIf { it.isNotBlank() }?.let { shown(key, it, facts, region) },
    )
}

private fun shown(key: String, value: String, facts: Map<String, String>, region: String): String =
    if (key == META_ENTITY_URL) readableLink(value) else shownKnowledge(key, value, facts, region)

/** Ссылка человеку — без схемы и хвостовой косой черты, как в строке знания. */
private fun readableLink(value: String): String =
    value.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

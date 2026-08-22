package com.point.core.flow

/**
 * Чьё это знание (#1176, RFC Semantic Graph §6).
 *
 * Одних фактов мало: у почтовой наклейки два места и два имени, и всё решает то, что с чем
 * связано. Раньше эту связь выражали два частных правила — «место получателя» (#772) и «чей
 * телефон» (#747), — и класть её было некуда: правило подменяло значение факта, а сама
 * принадлежность нигде не оставалась. Оттого и «Сохранить контакт» брало имя по порядку
 * ключей, а не по тому, чей это номер.
 *
 * Связь — обычная аннотация ключа, как `.src` и `.ev`: `entity.phone.of=graph.role.sender`
 * читается «телефон относится к отправителю». Значением аннотации стоит КЛЮЧ другого знания
 * того же объекта, а не копия его значения: второго экземпляра имени не появляется, а само
 * имя всегда берётся оттуда, где оно живёт.
 *
 * Нового хранилища связь не заводит: она едет тем же merge, тем же снимком и тем же
 * переносом на компьютер, что и остальные аннотации.
 */
const val META_OF_SUFFIX = ".of"

/** Ключ знания, к которому относится [key], — только если то знание и правда есть. */
fun ownerKeyOf(facts: Map<String, String>, key: String): String? =
    facts[key + META_OF_SUFFIX]
        ?.takeIf { it.isNotBlank() && !facts[it].isNullOrBlank() }

/** Значение знания, к которому относится [key]: имя стороны берётся у неё, а не при факте. */
fun ownerOf(facts: Map<String, String>, key: String): String? =
    ownerKeyOf(facts, key)?.let { facts[it] }

/** Прочтение и сторона, при которой оно стоит. */
data class PartyReading(val reading: FieldCandidate, val partyKey: String)

/**
 * Знание при своей стороне (#1176).
 *
 * Одно правило вместо двух частных: блоки страницы (#764, #768) уже держат колонку при её
 * подписи, и прочтение, стоящее в одном блоке ровно с одной названной стороной, — про неё.
 * Работает для любого факта, а не только для телефона и места: сторона названа ролью, факт
 * назван ключом, а страница говорит, кто где стоит.
 *
 * Правило узкое намеренно. Одноколоночная страница ничего не разделяет; блок с двумя
 * сторонами не отдаёт ни одной; имя, встреченное на странице дважды, места не называет.
 * Догадка о том, кому из двоих принадлежит номер, была бы выдумкой, а не знанием.
 *
 * Слово модели сильнее страницы: если владелец назван при самом значении (пара «номер | имя»),
 * геометрия не спрашивается — её ответ и так не станет главнее.
 */
fun AtomLayer.belongings(
    readings: Map<String, List<FieldCandidate>>,
    parties: Map<String, String>,
): Map<String, List<PartyReading>> {
    if (readings.isEmpty() || parties.isEmpty()) return emptyMap()

    val blocks = blocks()
    if (blocks.size < 2) return emptyMap()

    val blockById = HashMap<String, Int>()
    blocks.forEachIndexed { index, block -> block.forEach { blockById[it.id] = index } }

    fun blockOf(ids: List<String>): Int? =
        ids.map(::bareIndexId).mapNotNull(blockById::get).distinct().singleOrNull()

    // Одно имя в двух ролях — один человек, а не две стороны в одном блоке.
    val partyBlocks = parties.entries
        .filter { it.value.isNotBlank() }
        .distinctBy { normConsensus(it.value) }
        .mapNotNull { (key, name) ->
            findOnPage(name).singleOrNull()?.ids?.let(::blockOf)?.let { key to it }
        }
    if (partyBlocks.isEmpty()) return emptyMap()

    return readings.mapValues { (_, list) ->
        list.filter { it.person == null && it.ids.isNotEmpty() }.mapNotNull { reading ->
            val where = blockOf(reading.ids) ?: return@mapNotNull null
            partyBlocks.filter { (_, block) -> block == where }
                .singleOrNull()
                ?.let { (party, _) -> PartyReading(reading, party) }
        }
    }.filterValues { it.isNotEmpty() }
}

/**
 * Спор однозначного факта решает сторона, которой документ адресован (#1176).
 *
 * Место при отправителе — «откуда», место при получателе — «куда»: судье полей два отделения
 * наклейки одинаковы, и побеждало первое, то есть склад отправления. Однозначный факт
 * документа говорит про ту сторону, которой документ адресован, — её прочтение и становится
 * значением.
 *
 * Многозначный факт (#652) так не судится: второй телефон — не спор первого, а другая штука,
 * и каждый остаётся при своём хозяине.
 */
fun chosenByAddressee(key: String, belongings: List<PartyReading>): PartyReading? {
    if (isMultiValueFact(key)) return null
    return belongings.singleOrNull { it.partyKey in ADDRESSED_PARTIES }
}

/** Ключи сторон, которым документ адресован. */
val ADDRESSED_PARTIES: Set<String> = CLASSIFIER_ROLES
    .filter { it.addressed }
    .mapTo(LinkedHashSet()) { META_GRAPH_ROLE_PREFIX + it.key }

/**
 * Принадлежность как аннотация ключа (#1176).
 *
 * Записывается только про то значение, которое у факта сейчас и стоит: если главным осталось
 * другое прочтение, связи нет — и обещать её нечем.
 */
fun belongingFacts(
    facts: Map<String, String>,
    belongings: Map<String, List<PartyReading>>,
): Map<String, String> = belongings.mapNotNull { (key, readings) ->
    val value = facts[key]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    readings.firstOrNull { normConsensus(it.reading.text) == normConsensus(value) }
        ?.let { key + META_OF_SUFFIX to it.partyKey }
}.toMap()

/**
 * Телефон, принадлежащий человеку, — его контакт (#653, #1176).
 *
 * Пара «имя + номер» больше не выводится отдельным правилом под наклейку: она читается из
 * связи. Служба не человек — это решает вид узла стороны, а не имя роли: у перевозчика
 * карточки контакта не заводится, потому что «Нова пошта» не человек, и у отправителя-ФОП
 * не заводится по той же причине.
 */
fun personContacts(
    belongings: List<PartyReading>,
    parties: Map<String, String>,
): List<PersonContact> = belongings.mapNotNull { belonging ->
    val role = roleOfKey(belonging.partyKey) ?: return@mapNotNull null
    val name = parties[belonging.partyKey]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    PersonContact(name, belonging.reading.text).takeIf { role.kindFor(name) == KIND_PERSON }
}

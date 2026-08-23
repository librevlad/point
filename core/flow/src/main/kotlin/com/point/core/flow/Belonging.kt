package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.Relation
import com.point.core.model.RelationType

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

/**
 * Ключ, называющий знание об объекте: сущность, роль стороны, ячейка (#1176).
 *
 * Хозяин бывает только у такого знания. Просьба к компьютеру живёт своим пространством имён,
 * и `exec.of` — родословная приехавшего результата (ADR-0001 §2), а не хозяин факта «exec»,
 * которого не существует. Без этой мерки снятие устаревшей принадлежности срезало бы заодно
 * и родословную.
 */
fun isKnowledgeKey(key: String): Boolean =
    key.startsWith(META_ENTITY_PREFIX) ||
        key.startsWith(META_GRAPH_ROLE_PREFIX) ||
        key.startsWith(META_CELL_PREFIX)

/** Аннотация принадлежности: `.of` при знании объекта, а не любой ключ с тем же хвостом. */
fun isBelongingKey(key: String): Boolean = key.endsWith(META_OF_SUFFIX) && isKnowledgeKey(key)

/** Ключ знания, к которому относится [key], — только если то знание и правда есть. */
fun ownerKeyOf(facts: Map<String, String>, key: String): String? =
    facts[key + META_OF_SUFFIX]
        ?.takeIf { it.isNotBlank() && !facts[it].isNullOrBlank() }

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
 * Служба — такая же сторона: прежнее правило (#747) не пускало перевозчика к геометрии, и
 * его имя в колонке человека ничего не меняло. Теперь оно, как любое второе имя в блоке,
 * связь снимает: чей номер стоит между человеком и службой — страница не говорит, и Point
 * не догадывается. На настоящей наклейке это ничего не стоит: среди слов колонок имени
 * службы нет, и названный перевозчик ни одной связи не меняет (BelongingTest).
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
 * Хозяина называет то самое решение, что и значение: [chosen] — прочтение, ставшее значением,
 * вместе со стороной, при которой оно стояло. Узнавать это прочтение постфактум по тексту
 * было нельзя: воронка судьи меняет текст — слово страницы встаёт вместо слова модели (#809),
 * — и связь, выбранная судьёй, не находилась и молча пропадала. На экране у факта не было
 * хозяина, который у него есть.
 *
 * Записывается только про то знание, которое сейчас и стоит: слияние могло оставить главным
 * другое прочтение — тогда связи нет, и обещать её нечем. Мерка та же, что у [staleBelongings].
 */
fun belongingFacts(
    facts: Map<String, String>,
    chosen: Map<String, PartyReading>,
): Map<String, String> = chosen.mapNotNull { (key, party) ->
    val value = facts[key]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    (key + META_OF_SUFFIX to party.partyKey).takeIf { stillSameFact(key, party.reading.text, value) }
}.toMap()

/**
 * То же ли это знание, при котором сказана принадлежность (#1176).
 *
 * Одна мерка на три вопроса: записать хозяина, снять устаревшего и понять, что у узла
 * сменился факт. То же знание, записанное иначе, и починка искажения — тот же факт.
 */
fun stillSameFact(key: String, was: String, now: String): Boolean =
    sameFact(key, was, now) || isRepairOf(was, now)

/**
 * Принадлежность сказана про тот факт, который стоял (#1176).
 *
 * Прочтение увидели при отправителе — про его номер это и сказано. Стал главным другой факт —
 * человек исправил номер, виток прочёл иначе, — и прежнее наблюдение уже не про него: новый
 * номер стоял бы подписанный старым хозяином. Своё наблюдение приносит тот, кто кладёт
 * значение; не принёс — связи нет, и выдумывать её нечем.
 *
 * То же знание, записанное иначе, и починка искажения — тот же факт, и принадлежность при
 * нём остаётся: мерка та же, что у слияния фактов.
 */
fun staleBelongings(known: Map<String, String>, merged: Map<String, String>): Set<String> =
    known.keys
        .filter(::isBelongingKey)
        .filterTo(LinkedHashSet()) { ofKey ->
            val key = ofKey.removeSuffix(META_OF_SUFFIX)
            !stillSameFact(key, known[key].orEmpty(), merged[key].orEmpty())
        }

/**
 * Связи кадра после слияния (#1176).
 *
 * Связи копятся: находка остаётся находкой. Принадлежность — не «ещё одна связь», а
 * наблюдение о том, чьё значение сейчас: у номера один хозяин. После второго витка у узла
 * оказывались две, старая шла первой, и человеку показывался хозяин с прошлого витка.
 * Свежая занимает место прежней — как `.of` в знании объекта заменяется, а не складывается.
 *
 * Узел, чей факт сменился ([renamed]), принадлежности лишается: она была сказана про прежнее
 * значение, а не про узел вообще, — ровно та же мерка, что у [staleBelongings].
 */
fun mergedRelations(
    known: List<Relation>,
    fresh: List<Relation>,
    renamed: Set<String> = emptySet(),
): List<Relation> {
    val replaced = fresh.filter { it.type == RelationType.BELONGS_TO }.mapTo(HashSet()) { it.fromId } + renamed
    return (known.filterNot { it.type == RelationType.BELONGS_TO && it.fromId in replaced } + fresh).distinct()
}

/** Узлы, чей факт после слияния стал другим: прежнее о значении сказано не про них. */
fun renamedNodes(before: List<PointObject>, after: List<PointObject>): Set<String> {
    val now = after.associateBy { it.id }
    return before.mapNotNullTo(LinkedHashSet()) { was ->
        val (key, value) = factOf(was.metadata) ?: return@mapNotNullTo null
        was.id.takeUnless { stillSameFact(key, value, now[was.id]?.metadata?.get(key).orEmpty()) }
    }
}

/** Лицо узла — первое знание о нём: тем же ключом узел и заводился. */
fun factOf(metadata: Map<String, String>): Pair<String, String>? =
    metadata.entries.firstOrNull { (key, value) ->
        isKnowledgeKey(key) && !isAnnotationKey(key) && !isStateKey(key) && value.isNotBlank()
    }?.toPair()

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

package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.RelationType

data class ClassifierRole(

    val key: String,

    val kind: ObjectKind,

    val relation: RelationType,

    val question: String,

    val usuallyPerson: Boolean = false,

    /**
     * Сторона, которой документ адресован (#1176).
     *
     * У документа несколько сторон, и знание при них разное: место при отправителе — «откуда»,
     * место при получателе — «куда». Когда прочтения однозначного факта стоят при разных
     * сторонах, документ говорит про ту, которой он адресован. Это свойство роли, а не тип
     * документа: письмо, накладная и счёт написаны получателю одинаково.
     */
    val addressed: Boolean = false,

    val markers: Set<String> = emptySet(),
)

fun ClassifierRole.isRoleLabel(word: String): Boolean =
    word.trim().trimEnd(':', '.', '—', '-').lowercase() in markers

fun ClassifierRole.kindFor(value: String): ObjectKind =
    if (usuallyPerson && value.split(WORD_SPLIT).none { it.trim('«', '»', '"', '\'', '(', ')').lowercase() in LEGAL_FORMS }) {
        KIND_PERSON
    } else {
        kind
    }

private val LEGAL_FORMS = setOf(
    "тов", "фоп", "пп", "ат", "пат", "прат", "кп", "ооо", "ип", "зао", "оао", "ано",
    "llc", "ltd", "inc", "gmbh", "corp", "co",
)

private val WORD_SPLIT = Regex("""[\s.,]+""")

val CLASSIFIER_ROLES: List<ClassifierRole> = listOf(
    ClassifierRole(
        "sender", KIND_ORGANIZATION, RelationType.SENDER, "отправитель груза или письма",
        usuallyPerson = true,
        markers = setOf("відправник", "вйдправник", "отправитель", "sender", "from", "від", "от"),
    ),
    ClassifierRole(
        "receiver", KIND_ORGANIZATION, RelationType.RECEIVER, "получатель груза или письма",
        usuallyPerson = true, addressed = true,
        markers = setOf("отримувач", "одержувач", "получатель", "receiver", "recipient", "кому", "to"),
    ),
    ClassifierRole(
        "carrier", KIND_ORGANIZATION, RelationType.CARRIER, "перевозчик",
        markers = setOf("перевізник", "перевозчик", "carrier", "доставка"),
    ),
    ClassifierRole(
        "issuer", KIND_ORGANIZATION, RelationType.ISSUED_BY, "кто выдал документ",
        markers = setOf("видав", "видано", "выдал", "выдан", "issuer", "issued"),
    ),
)

const val META_GRAPH_ROLE_PREFIX = "graph.role."

/** Роль по ключу знания: `graph.role.sender` — это роль отправителя. */
fun roleOfKey(key: String): ClassifierRole? =
    CLASSIFIER_ROLES.firstOrNull { META_GRAPH_ROLE_PREFIX + it.key == key }

/**
 * Идентичность стороны (#1176).
 *
 * «НОВІК» из роли отправителя и «НОВІК» из пары «имя + номер» — один человек, а не два узла.
 * Формула была списана в трёх местах; узел стороны один, значит и формула одна.
 */
fun partyNodeId(sourceId: String, name: String): String = "$sourceId:party:${normalizedParty(name)}"

fun normalizedParty(name: String): String = name.lowercase().replace(PARTY_SPACES, " ").trim()

private val PARTY_SPACES = Regex("""\s+""")

data class Classified(val role: ClassifierRole, val element: LayoutElement)

fun parseClassification(
    answer: String,
    elements: List<LayoutElement>,
    roles: List<ClassifierRole> = CLASSIFIER_ROLES,
): List<Classified> {
    val byId = elements.associateBy { it.id.uppercase() }
    val byKey = roles.associateBy { it.key }
    val taken = mutableSetOf<String>()
    return answer.lineSequence().mapNotNull { raw ->
        val line = raw.trim()
        val eq = line.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val role = byKey[line.substring(0, eq).trim().lowercase()] ?: return@mapNotNull null
        val element = byId[line.substring(eq + 1).trim().uppercase()] ?: return@mapNotNull null

        if (!taken.add(role.key)) return@mapNotNull null
        Classified(role, element)
    }.toList()
}

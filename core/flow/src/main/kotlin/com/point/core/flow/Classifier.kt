package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.RelationType

/**
 * The model as a **classifier over layout**, never as an oracle over the graph (#222, шаг 6).
 *
 * It is shown paragraphs with local ids and asked one thing: which element plays which role. It
 * answers with ids. Everything else — what kind of thing that is, what object to create, how it
 * relates to the document — is decided [by code][CLASSIFIER_ROLES], from a table a human wrote.
 *
 * **What this buys.** The answer becomes machine-checkable. `P7` either exists among the elements
 * or it does not; an invented id is dropped without a human ever seeing it. Today an LLM answer is
 * free prose, and there is no way at all to tell a good one from a confident wrong one.
 *
 * The Point graph is not in the prompt and cannot be: [classifierPrompt] takes elements and roles,
 * and there is no parameter through which an object, a kind or a relation could reach it.
 */
data class ClassifierRole(
    /** The word the model answers with — short, lowercase, ASCII, never translated. */
    val key: String,
    /** What such a thing IS. Decided here, not by the model. */
    val kind: ObjectKind,
    /** How it stands to the document. Roles live in relations, never in kinds. */
    val relation: RelationType,
    /** How the role is put to the model, in the user's language. */
    val question: String,
)

/**
 * The roles worth asking about, and what each one means in the graph.
 *
 * One line per role, and nothing else changes: no new kind, no new capability, no new parsing.
 * That is the promise of «экстрактор с крошечным ТЗ» made concrete.
 *
 * Only organisations for now — deliberately. Addresses, dates and identifiers already come off
 * the page by rule, for free and offline; asking a paid model to repeat that work would be worse
 * in every way. What a rule genuinely cannot do is say **who is who**, and that is what is here.
 */
val CLASSIFIER_ROLES: List<ClassifierRole> = listOf(
    ClassifierRole("sender", KIND_ORGANIZATION, RelationType.SENDER, "отправитель груза или письма"),
    ClassifierRole("receiver", KIND_ORGANIZATION, RelationType.RECEIVER, "получатель груза или письма"),
    ClassifierRole("carrier", KIND_ORGANIZATION, RelationType.CARRIER, "перевозчик"),
    ClassifierRole("issuer", KIND_ORGANIZATION, RelationType.ISSUED_BY, "кто выдал документ"),
)

/**
 * Metadata key prefix for a classified role: `graph.role.sender` → the element's own text.
 *
 * One key per role, and the value is the text the model pointed at — nothing about kinds,
 * ids or relations is written down, because all three are decided by the role table in code.
 * Being plain metadata, it is journaled with the object and survives process death for free.
 */
const val META_GRAPH_ROLE_PREFIX = "graph.role."

/** The model's way of saying «ничего из этого в документе нет» — an answer, not a failure. */
const val CLASSIFIER_NOTHING = "НЕТ"

/** One survived reading: this element plays this role. Built only from a validated id. */
data class Classified(val role: ClassifierRole, val element: LayoutElement)

/**
 * The prompt: the elements, the roles, and a format with no room for prose.
 *
 * Note what is absent. No object ids, no kinds, no relations, no earlier findings — the graph has
 * no route into this string.
 */
fun classifierPrompt(
    elements: List<LayoutElement>,
    roles: List<ClassifierRole> = CLASSIFIER_ROLES,
): String = buildString {
    append("Ниже элементы документа, у каждого свой идентификатор.\n\n")
    elements.forEach { append(it.id).append(": ").append(it.text).append('\n') }
    append("\nОпредели, какой элемент играет каждую из ролей:\n")
    roles.forEach { append("- ").append(it.key).append(" — ").append(it.question).append('\n') }
    append(
        "\nОтвечай ТОЛЬКО строками вида роль=идентификатор, по одной на строку, без пояснений.\n" +
            "Идентификатор — РОВНО один из перечисленных выше, а не текст элемента.\n" +
            "Роль, которой в документе нет, пропусти.\n" +
            "Если не нашёл ни одной роли — ответь ровно $CLASSIFIER_NOTHING.\n",
    )
}

/**
 * Parses the answer and throws away everything that is not a reference to a real element.
 *
 * This is the whole point of the design, so it is strict on purpose:
 * - an id that is not among [elements] — dropped, however confident the model sounded;
 * - the element's *text* instead of its id — dropped, because that is prose wearing an id's hat;
 * - an unknown role — dropped;
 * - the first reading of a role wins, so a model that repeats itself cannot double the graph.
 *
 * An empty list is a legitimate outcome: the document may simply have no parties in it.
 */
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
        // First *valid* reading wins — a rejected line must not use up its role, or a model
        // that guesses once and then answers properly would lose the good answer.
        if (!taken.add(role.key)) return@mapNotNull null
        Classified(role, element)
    }.toList()
}

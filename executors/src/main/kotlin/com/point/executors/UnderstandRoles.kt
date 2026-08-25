package com.point.executors

import com.point.core.flow.AtomAddress
import com.point.core.flow.AtomLayer
import com.point.core.flow.CLASSIFIER_ROLES
import com.point.core.flow.LayoutElement
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.flow.bareIndexId
import com.point.core.flow.isRepairOf
import com.point.core.flow.isRoleLabel
import com.point.core.flow.normConsensus
import com.point.core.flow.parseClassification
import com.point.core.flow.plausiblePersonName
import com.point.core.flow.resolve
import com.point.core.flow.splitCandidate

/**
 * Кто играет роли на странице — кто отправил, кто получил, кто выдал (#654, #698).
 *
 * [values] — принятые роли; [disputes] — роль, где страница и модель прочли разное;
 * [blocked] — прочтения, не прошедшие правдоподобия имени (#1032). След обязателен: прежде
 * отброшенное исчезало молча, ролей не оставалось вовсе, и вопрос «кто играет роли»
 * закрывался как «не нашлось» — а его смотрели и ответа не приняли.
 */
internal data class RoleReadings(
    val values: Map<String, String>,
    val disputes: Map<String, List<String>>,
    val blocked: Map<String, List<String>> = emptyMap(),
)

internal fun roleReadings(
    answer: String,
    elements: List<LayoutElement>,
    layer: AtomLayer?,
): RoleReadings {
    val named = parseClassification(answer, elements)
        .associate { META_GRAPH_ROLE_PREFIX + it.role.key to it.element.text }
    val (plausible, implausible) = named.entries.partition { plausiblePersonName(it.value) }
    val fromElements = plausible.associate { it.key to it.value }
    val blocked = LinkedHashMap<String, MutableList<String>>()
    implausible.forEach { blocked.getOrPut(it.key) { mutableListOf() }.add(it.value) }
    if (layer == null) return RoleReadings(fromElements, emptyMap(), blocked)

    val byKey = CLASSIFIER_ROLES.associateBy { it.key }
    val values = LinkedHashMap<String, String>()
    val disputes = LinkedHashMap<String, List<String>>()
    answer.lineSequence().forEach { raw ->
        val line = raw.trim()
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val role = byKey[line.substring(0, eq).trim().lowercase()] ?: return@forEach
        val metaKey = META_GRAPH_ROLE_PREFIX + role.key
        if (metaKey in values) return@forEach
        val candidate = splitCandidate(line.substring(eq + 1).trim()) ?: return@forEach
        if (candidate.ids.isEmpty()) return@forEach

        val idsByAtom = layer.atoms.associateBy { it.id }
        val pointed = candidate.ids.map(::bareIndexId)
        val withoutLabel = pointed.filterNot { id ->
            idsByAtom[id]?.text?.let { role.isRoleLabel(it) } == true
        }
        val resolved = layer.resolve(AtomAddress.ByIds(withoutLabel.ifEmpty { pointed }))
        if (resolved.atoms.isEmpty()) return@forEach
        val page = resolved.text
        val model = candidate.text
        val chosen = when {
            normConsensus(model) == normConsensus(page) -> page
            isRepairOf(page, model) -> model
            else -> {
                if (plausiblePersonName(page)) disputes[metaKey] = listOf(page, model)
                page
            }
        }
        if (!plausiblePersonName(chosen)) {
            disputes.remove(metaKey)
            val seen = blocked.getOrPut(metaKey) { mutableListOf() }
            if (chosen !in seen) seen.add(chosen)
            return@forEach
        }
        values[metaKey] = chosen
    }

    fromElements.forEach { (key, text) -> values.putIfAbsent(key, text) }
    return RoleReadings(values, disputes, blocked)
}

package com.point.core.model

data class Bubble(
    val icon: String,
    val title: String,
    val capabilityId: CapabilityId,
    val expectedNextState: ObjectState,
    val tier: BubbleTier = BubbleTier.SMART,

    val intent: Intent = Intent.UNDERSTAND,

    val yields: ActionYield = ActionYield.Unknown,

    /**
     * Причина, по которой исходник негоден (#684/#685, `Feature.UNUSABLE`) — если объект уже
     * так отмечен. Дверь остаётся в списке: причина становится подписью там, где второй
     * строки обычно нет (#582), а не прячет действие (решение владельца — «дверь не исчезает»).
     */
    val unusableReason: String? = null,
)

fun keepShownOrder(shown: List<Bubble>, fresh: List<Bubble>): List<Bubble> {
    if (shown.isEmpty()) return fresh
    val seen = shown.withIndex().associate { (i, b) -> b.capabilityId to i }
    return fresh.sortedBy { seen[it.capabilityId] ?: Int.MAX_VALUE }
}

enum class BubbleTier { INSTANT, SMART, AI }

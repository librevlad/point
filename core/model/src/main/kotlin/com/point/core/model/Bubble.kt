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

/**
 * Пространство действий расширилось знанием — порядок на экране не переставляется под пальцем.
 *
 * Показанные действия остаются на своих местах: человек уже прицелился, и уезжающая строка —
 * промах вместо действия.
 *
 * Новое действие при этом дописывалось в самый конец, и знание не поднимало своего действия
 * (#937): человек делится ссылкой, «Открыть ссылку» появляется вместе с найденной ссылкой —
 * то есть позже всех, — и встаёт одиннадцатым, под свёрткой, ниже предложения сделать из
 * ссылки таблицу Excel.
 *
 * Теперь новое действие встаёт туда, куда его ставит ранжирование: перед первым показанным
 * действием, которое ранжирование ставит ниже него. Показанные при этом друг друга не
 * обгоняют — двигаться под пальцем по-прежнему нечему.
 */
fun keepShownOrder(shown: List<Bubble>, fresh: List<Bubble>): List<Bubble> {
    if (shown.isEmpty()) return fresh
    val seen = shown.withIndex().associate { (i, b) -> b.capabilityId to i }

    // Место новичка — там, где ранжированный список упирается в уже показанное.
    val place = IntArray(fresh.size)
    var next = shown.size
    for (i in fresh.indices.reversed()) {
        val at = seen[fresh[i].capabilityId]
        if (at != null) next = at
        place[i] = at ?: next
    }
    return fresh.indices
        .sortedWith(compareBy({ place[it] }, { if (fresh[it].capabilityId in seen) 1 else 0 }, { it }))
        .map { fresh[it] }
}

enum class BubbleTier { INSTANT, SMART, AI }

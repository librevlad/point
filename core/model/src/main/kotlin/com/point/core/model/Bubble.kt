package com.point.core.model

/**
 * A single action offered on the current object.
 *
 * The set of bubbles for a state IS the Flow Graph, derived from Executor
 * contracts — there is no separate stored transition map. [expectedNextState]
 * is the graph edge this bubble represents (from `Executor.produces`).
 *
 * @param icon icon key, resolved to a drawable/vector by :core:ui.
 */
data class Bubble(
    val icon: String,
    val title: String,
    val capabilityId: CapabilityId,
    val expectedNextState: ObjectState,
    val tier: BubbleTier = BubbleTier.SMART,
    /** The user [Intent] this action primarily serves (its first served intent in declaration
     *  order). Set by the registry from the capability; the object screen groups actions by it —
     *  Извлечь (UNDERSTAND) / Превратить (PREPARE) / Отправить (OPEN, SEND). */
    val intent: Intent = Intent.UNDERSTAND,
    /**
     * Что вернётся, если нажать (#491) — объявление самой способности, а не догадка экрана.
     *
     * Не выводится из [expectedNextState]: тот собран как `produces(state) ?: state`, и в одном
     * значении слиты три разных ответа — «ничего не вернёт», «вернёт этот же объект понятым» и
     * «неизвестно, пока не спросишь». Человеку они не одно и то же.
     */
    val yields: ActionYield = ActionYield.Unknown,
)

/**
 * Порядок уже показанных действий не меняется под пальцем.
 *
 * Разбор объекта идёт в фоне и заканчивается через секунды после того, как экран нарисован: Point
 * узнаёт, что на фотографии текст, и набор действий пересобирается. Раньше он пересобирался
 * целиком — со своим ранжированием, — и строка, в которую человек уже целился, уезжала, а на её
 * место вставала другая. Тап, нацеленный в бесплатное локальное чтение, попадал в платное сетевое
 * действие: цена решения менялась между взглядом и касанием.
 *
 * Поэтому: показанное остаётся на своих местах в прежнем порядке, новое дописывается следом.
 * Ранжирование не отменяется — оно решает судьбу только тех действий, которых человек ещё не видел.
 */
fun keepShownOrder(shown: List<Bubble>, fresh: List<Bubble>): List<Bubble> {
    if (shown.isEmpty()) return fresh
    val seen = shown.withIndex().associate { (i, b) -> b.capabilityId to i }
    return fresh.sortedBy { seen[it.capabilityId] ?: Int.MAX_VALUE }
}

/**
 * The action's visual weight class (#114) — derived from the capability's meta, never
 * hand-assigned: INSTANT (local, immediate — copy/share/open), SMART (real on-device
 * work — recognise/transform), AI (leaves the device — cloud models). The three levels
 * look different on screen so the user can feel an action's nature before tapping.
 */
enum class BubbleTier { INSTANT, SMART, AI }

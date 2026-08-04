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
 * The action's visual weight class (#114) — derived from the capability's meta, never
 * hand-assigned: INSTANT (local, immediate — copy/share/open), SMART (real on-device
 * work — recognise/transform), AI (leaves the device — cloud models). The three levels
 * look different on screen so the user can feel an action's nature before tapping.
 */
enum class BubbleTier { INSTANT, SMART, AI }

package com.point.core.model

/**
 * A capability that *almost* applies to the current object (#97 negotiation): shown dimmed, with
 * [missing] explaining what one signal it needs — so the user discovers the power and how to unlock
 * it, without it cluttering the real action set. Not directly runnable (the prerequisite comes first).
 */
data class LatentBubble(
    val icon: String,
    val title: String,
    val missing: String,
)

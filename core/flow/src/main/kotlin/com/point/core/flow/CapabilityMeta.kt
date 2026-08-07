package com.point.core.flow

data class CapabilityMeta(

    val priority: Int = 50,
    val cost: Cost = Cost.LOCAL,
    val latency: Latency = Latency.INSTANT,

    val network: Boolean = false,

    val auth: Boolean = false,

    val localOnly: Boolean = false,
)

enum class Cost { FREE, LOCAL, PAID }

enum class Latency { INSTANT, FAST, SLOW }

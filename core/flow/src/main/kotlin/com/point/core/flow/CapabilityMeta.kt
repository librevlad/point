package com.point.core.flow

data class CapabilityMeta(

    val priority: Int = 50,
    val cost: Cost = Cost.LOCAL,
    val latency: Latency = Latency.INSTANT,

    val network: Boolean = false,

    val auth: Boolean = false,

    val localOnly: Boolean = false,

    /**
     * Исследовательская Capability — её результат это знание в Graph, а не новый объект или
     * внешний эффект (ADR-0001 §11).
     *
     * Механизм у неё общий с пользовательским действием, поэтому различие объявляется здесь:
     * ни `produces`, ни `yields`, ни `intents` его выразить не могут — у `Понять` они те же.
     * Planner такие Capability человеку не предлагает, и наружу они не рекламируются.
     */
    val investigation: Boolean = false,

    /**
     * Что действие потенциально принесёт (ADR-0001 §11 — «что потенциально получается»).
     *
     * Discovery ranking пользуется этим, чтобы не запускать дорогое исследование, которое
     * ничего не откроет.
     */
    val mayYield: Set<com.point.core.model.Feature> = emptySet(),

    val mayYieldKinds: Set<com.point.core.model.ObjectKind> = emptySet(),

    /**
     * Действие достаёт то, что было внутри, а не делает новое (#946).
     *
     * Распаковка архива, страницы документа, вложения набора: объект был внутри исходника, и
     * связь между ними — «содержит». Всё остальное — «получено из»: запись, сделанная из
     * текста, внутри текста не лежала.
     *
     * Решение владельца 13.08.2026: связи разные и в графе не сливаются.
     */
    val revealsInside: Boolean = false,
)

enum class Cost { FREE, LOCAL, PAID }

enum class Latency { INSTANT, FAST, SLOW }

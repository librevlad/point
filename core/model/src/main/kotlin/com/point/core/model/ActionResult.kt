package com.point.core.model

sealed interface ActionResult {

    data class Success(val result: ResultObject) : ActionResult

    /**
     * Шаг состоялся без нового объекта — ADR-0001 §18.
     *
     * Это либо внешний side-effect (тогда [findings] нет), либо новое знание в Graph:
     * исследование возвращает его именно здесь.
     */
    data class Done(val message: String, val findings: Findings? = null) : ActionResult

    data class Failure(val reason: String, val recoverable: Boolean) : ActionResult

    data class NeedsInput(val prompt: String, val suggestions: List<String> = emptyList()) : ActionResult

    data class NeedsImage(val prompt: String) : ActionResult
}

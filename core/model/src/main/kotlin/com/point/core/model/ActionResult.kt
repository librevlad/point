package com.point.core.model

sealed interface ActionResult {

    data class Success(val result: ResultObject) : ActionResult

    data class Done(val message: String) : ActionResult

    data class Failure(val reason: String, val recoverable: Boolean) : ActionResult

    data class NeedsInput(val prompt: String, val suggestions: List<String> = emptyList()) : ActionResult

    data class NeedsImage(val prompt: String) : ActionResult
}

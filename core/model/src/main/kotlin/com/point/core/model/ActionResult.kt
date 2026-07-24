package com.point.core.model

/**
 * Explicit success / failure / needs-input channel for every step.
 *
 * An invisible chain with no error handling is a debugging and trust trap (an
 * OCR miss silently yields a garbage spreadsheet). Making the outcome a sealed
 * type forces every caller to handle the recoverable and interactive cases.
 */
sealed interface ActionResult {

    data class Success(val result: ResultObject) : ActionResult

    /** A terminal action completed with no new object (Share, Save). Carries a
     *  short confirmation for the UI; the flow stays on the current frame. */
    data class Done(val message: String) : ActionResult

    /** [recoverable] == true means the user can retry / amend the same step. */
    data class Failure(val reason: String, val recoverable: Boolean) : ActionResult

    /**
     * The executor needs more input before it can proceed. [suggestions] are optional ready-made
     * answers (e.g. the 3 most likely AI prompts for this object, #86) the user can tap instead of
     * typing.
     */
    data class NeedsInput(val prompt: String, val suggestions: List<String> = emptyList()) : ActionResult

    /**
     * The executor needs the user to pick an **image** before it can proceed (e.g. a background to
     * composite onto). The host opens the system photo picker; the chosen URI is fed back as the
     * step's amendment.
     */
    data class NeedsImage(val prompt: String) : ActionResult
}

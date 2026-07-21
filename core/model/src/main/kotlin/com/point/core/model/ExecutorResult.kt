package com.point.core.model

/**
 * Explicit success / failure / needs-input channel for every step.
 *
 * An invisible chain with no error handling is a debugging and trust trap (an
 * OCR miss silently yields a garbage spreadsheet). Making the outcome a sealed
 * type forces every caller to handle the recoverable and interactive cases.
 */
sealed interface ExecutorResult {

    data class Success(val result: ResultObject) : ExecutorResult

    /** A terminal action completed with no new object (Share, Save). Carries a
     *  short confirmation for the UI; the flow stays on the current frame. */
    data class Done(val message: String) : ExecutorResult

    /** [recoverable] == true means the user can retry / amend the same step. */
    data class Failure(val reason: String, val recoverable: Boolean) : ExecutorResult

    /** The executor needs more input from the user before it can proceed. */
    data class NeedsInput(val prompt: String) : ExecutorResult
}

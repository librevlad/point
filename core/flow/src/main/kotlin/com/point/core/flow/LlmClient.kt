package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.ResultObject

/**
 * Gemini behind an interface — fakeable in tests.
 *
 * Never invoked on the first screen: the LLM only runs after the user picks an
 * action, from inside the AiExecutor. The [run] result is materialised into the
 * scratch store as a new object (e.g. a markdown answer written to a `.md` file).
 */
interface LlmClient {

    suspend fun run(obj: PointObject, prompt: String): ResultObject

    /**
     * Whether this client's model can actually consume [obj]. A text-only model returns
     * false for an image, so the fallback chain skips it instead of "succeeding" with a
     * "you didn't attach an image" reply and stopping there. Defaults to true (text is
     * universal); vision routing is what makes AI on a photo reliable.
     */
    fun canHandle(obj: PointObject): Boolean = true
}

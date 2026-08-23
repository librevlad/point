package com.point.data

import com.point.core.flow.ReaderPrivacy
import com.point.core.flow.ReaderPromise
import com.point.core.model.PointObject
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import com.point.core.flow.HttpJson

class OvhVisionReader(
    private val http: HttpJson,
    private val frames: OutboundFrames,
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
) : CloudTextReader {

    override val reader = READER

    override val privacy = ReaderPrivacy(
        where = "OVH, Франция (ЕС)",
        promise = ReaderPromise.NO_TRAINING,
    )

    override val configured = true

    private val root: String get() = baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')

    override suspend fun read(obj: PointObject): String {
        val frame = frames.of(obj) ?: error(com.point.core.flow.FRAME_NOT_READY)
        val image = JSONObject()
            .put("type", "image_url")
            .put(
                "image_url",
                JSONObject().put("url", "data:${frame.mime};base64,${base64(frame.bytes)}"),
            )
        val body = JSONObject()
            .put("model", model.ifBlank { DEFAULT_MODEL })

            .put("temperature", 0)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "text").put("text", PROMPT))
                                .put(image),
                        ),
                ),
            )
            .toString()

        val headers = if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")
        val res = http.post("$root/chat/completions", headers, body)
        if (res.code !in 200..299) error(refusal(res.code))
        return textOf(res.body)
    }

    private fun textOf(json: String): String {

        val answer = runCatching { JSONObject(json) }.getOrElse {
            error(com.point.core.flow.UNREADABLE_ANSWER)
        }
        return answer.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()
    }

    private fun refusal(code: Int): String = com.point.core.flow.serviceRefusal(code)

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val READER = "ovh-qwen-vl"
        const val DEFAULT_BASE_URL = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1"
        const val DEFAULT_MODEL = "Qwen2.5-VL-72B-Instruct"

        /** Та же просьба, что у всех остальных путей чтения (#840). */
        val PROMPT: String = com.point.core.flow.CLOUD_READING_PROMPT
    }
}

package com.point.data

import com.point.core.flow.Atom
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.model.PointObject
import org.json.JSONArray
import org.json.JSONObject

class UnstructuredAtomRecognizer(
    private val http: HttpFiles,
    private val frames: OutboundFrames,
    private val apiKey: String,
    private val apiUrl: String,
) : CloudAtomRecognizer {

    override val reader = READER

    override val configured: Boolean get() = apiKey.isNotBlank()

    override suspend fun read(obj: PointObject): AtomLayer {
        require(configured) { "$READER: ключ не задан" }
        val frame = frames.of(obj) ?: error("$READER: кадр не подготовлен — нечего отправлять")
        val res = http.postMultipart(
            url = apiUrl.ifBlank { DEFAULT_URL },
            headers = mapOf("unstructured-api-key" to apiKey),
            parts = listOf(
                FormPart.Binary("files", frame.fileName, frame.mime, frame.bytes),

                FormPart.Field("coordinates", "true"),

                FormPart.Field("strategy", "hi_res"),

                FormPart.Field("languages", "rus"),
                FormPart.Field("languages", "eng"),
                FormPart.Field("output_format", "application/json"),

            ),
        )
        if (res.code !in 200..299) error(refusal(res.code))
        return layerOf(res.body, frame)
    }

    private fun layerOf(json: String, frame: OutboundFrame): AtomLayer {

        val elements = runCatching { JSONArray(json) }.getOrElse {
            error("$READER: ответ не разобран — пробуем следующий")
        }
        val atoms = mutableListOf<Atom>()
        val texts = mutableListOf<String>()
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val text = element.optString("text").trim()
            if (text.isEmpty()) continue
            texts += text
            val meta = element.optJSONObject("metadata") ?: continue
            val box = boxOf(meta.optJSONObject("coordinates"), frame) ?: continue
            atoms += Atom(
                id = "$ID_SPACE${atoms.size}",
                text = text,
                box = box,

                confidence = meta.optDouble("detection_class_prob", 1.0).toFloat().coerceIn(0f, 1f),
                reader = READER,
                readerVersion = API_VERSION,

                page = (meta.optInt("page_number", 1) - 1).coerceAtLeast(0),
            )
        }
        return AtomLayer(
            atoms = atoms,

            readerText = texts.joinToString("\n").ifEmpty { null },
            transform = frame.transform,
        )
    }

    private fun boxOf(coordinates: JSONObject?, frame: OutboundFrame): Box? {
        val points = coordinates?.optJSONArray("points") ?: return null
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (i in 0 until points.length()) {
            val point = points.optJSONArray(i) ?: continue
            if (point.length() < 2) continue
            val x = point.optDouble(0).toFloat()
            val y = point.optDouble(1).toFloat()
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
        if (left > right || top > bottom) return null
        return frame.toRawFrame(
            Box(left, top, right, bottom),
            layoutWidth = coordinates.optDouble("layout_width", 0.0).toFloat(),
            layoutHeight = coordinates.optDouble("layout_height", 0.0).toFloat(),
        )
    }

    private fun refusal(code: Int): String = when (code) {
        402 -> "$READER: бесплатный лимит исчерпан (402) — покупать не идём, пробуем следующий"
        429 -> "$READER: слишком часто (429) — пробуем следующий"
        401, 403 -> "$READER: ключ не принят ($code)"
        else -> "$READER: сервис отказал (код $code) — пробуем следующий"
    }

    private companion object {
        const val READER = "unstructured"
        const val ID_SPACE = "un"

        const val API_VERSION = "general/v0"
        const val DEFAULT_URL = "https://api.unstructuredapp.io/general/v0/general"
    }
}

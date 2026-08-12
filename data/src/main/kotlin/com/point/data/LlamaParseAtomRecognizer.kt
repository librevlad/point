package com.point.data

import com.point.core.flow.Atom
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.model.PointObject
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import com.point.core.flow.HttpFiles
import com.point.core.flow.FormPart

class LlamaParseAtomRecognizer(
    private val http: HttpFiles,
    private val frames: OutboundFrames,
    private val apiKey: String,
    private val baseUrl: String,
    private val tier: String = DEFAULT_TIER,
) : CloudAtomRecognizer {

    override val reader = READER

    override val configured: Boolean get() = apiKey.isNotBlank()

    private val root: String get() = baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')

    private val auth: Map<String, String> get() = mapOf("Authorization" to "Bearer $apiKey")

    override suspend fun read(obj: PointObject): AtomLayer {
        require(configured) { "$READER: ключ не задан" }
        val frame = frames.of(obj) ?: error("$READER: кадр не подготовлен — нечего отправлять")
        val jobId = upload(frame)
        val result = awaitResult(jobId)
        return layerOf(result, frame)
    }

    private suspend fun upload(frame: OutboundFrame): String {
        val res = http.postMultipart(
            url = "$root/api/v2/parse/upload",
            headers = auth,
            parts = listOf(
                FormPart.Binary("file", frame.fileName, frame.mime, frame.bytes),
                FormPart.Field("configuration", configuration()),
            ),
        )
        if (res.code !in 200..299) error(refusal(res.code))

        val json = res.body.asJson() ?: error("$READER: ответ не разобран — пробуем следующий")
        val id = json.optString("id").ifBlank { json.optJSONObject("job")?.optString("id").orEmpty() }
        return id.ifBlank { error("$READER: задача не создана — пробуем следующий") }
    }

    private fun configuration(): String = JSONObject()
        .put("tier", tier)
        .put(
            "processing_options",
            JSONObject().put("ocr_parameters", JSONObject().put("languages", JSONArray(LANGUAGES))),
        )
        .toString()

    private suspend fun awaitResult(jobId: String): JSONObject {
        repeat(MAX_POLLS) { attempt ->
            if (attempt > 0) delay(POLL_MS)
            val res = http.get("$root/api/v2/parse/$jobId?expand=items", auth)
            if (res.code !in 200..299) error(refusal(res.code))
            val json = res.body.asJson() ?: error("$READER: ответ не разобран — пробуем следующий")
            when (val status = json.optJSONObject("job")?.optString("status")?.uppercase().orEmpty()) {
                "COMPLETED", "SUCCESS" -> return json
                "ERROR", "FAILED", "CANCELLED", "CANCELED" -> {

                    val said = json.optJSONObject("job")?.optString("error_message").orEmpty().trim().take(200)
                    error(
                        if (said.isEmpty()) "$READER: задача не выполнена — пробуем следующий"
                        else "$READER: задача не выполнена — $said",
                    )
                }
                else -> if (status.isEmpty() && json.has("items")) return json
            }
        }
        error("$READER: страница не прочитана за ${MAX_POLLS * POLL_MS / 1000} с")
    }

    private fun layerOf(result: JSONObject, frame: OutboundFrame): AtomLayer {
        val pages = result.optJSONObject("items")?.optJSONArray("pages") ?: JSONArray()
        val atoms = mutableListOf<Atom>()
        val texts = mutableListOf<String>()
        for (p in 0 until pages.length()) {
            val page = pages.optJSONObject(p) ?: continue
            val pageWidth = page.optDouble("page_width", page.optDouble("width", 0.0)).toFloat()
            val pageHeight = page.optDouble("page_height", page.optDouble("height", 0.0)).toFloat()
            val index = (page.optInt("page_number", p + 1) - 1).coerceAtLeast(0)
            val items = page.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val text = textOf(item)
                if (text.isEmpty()) continue
                texts += text
                val box = boxOf(item, frame, pageWidth, pageHeight) ?: continue
                atoms += Atom(
                    id = "$ID_SPACE${atoms.size}",
                    text = text,
                    box = box,
                    confidence = confidenceOf(item),
                    reader = READER,
                    readerVersion = API_VERSION,
                    page = index,
                )
            }
        }
        return AtomLayer(
            atoms = atoms,
            readerText = texts.joinToString("\n").ifEmpty { null },
            transform = frame.transform,
        )
    }

    private fun textOf(item: JSONObject): String =
        item.optString("value").trim().ifEmpty { item.optString("md").trim() }

    private fun boxOf(item: JSONObject, frame: OutboundFrame, pageWidth: Float, pageHeight: Float): Box? {
        val box = spansOf(item).mapNotNull { rectOf(it) }.reduceOrNull { a, b -> a.union(b) } ?: return null
        return frame.toRawFrame(box, layoutWidth = pageWidth, layoutHeight = pageHeight)
    }

    private fun confidenceOf(item: JSONObject): Float =
        spansOf(item)
            .filter { it.has("confidence") && !it.isNull("confidence") }
            .map { it.optDouble("confidence", 1.0).toFloat() }
            .minOrNull()
            ?.coerceIn(0f, 1f)
            ?: 1f

    private fun spansOf(item: JSONObject): List<JSONObject> =
        when (val raw = item.opt("bbox") ?: item.opt("bBox")) {
            is JSONArray -> (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
            is JSONObject -> listOf(raw)
            else -> emptyList()
        }

    private fun rectOf(span: JSONObject): Box? {
        if (!span.has("x") || !span.has("y")) return null
        val x = span.optDouble("x", 0.0).toFloat()
        val y = span.optDouble("y", 0.0).toFloat()
        val w = span.optDouble("w", span.optDouble("width", 0.0)).toFloat()
        val h = span.optDouble("h", span.optDouble("height", 0.0)).toFloat()
        if (w <= 0f || h <= 0f) return null
        return Box(x, y, x + w, y + h)
    }

    private fun String.asJson(): JSONObject? = runCatching { JSONObject(this) }.getOrNull()

    private fun refusal(code: Int): String = when (code) {
        402 -> "$READER: бесплатные кредиты кончились (402) — покупать не идём, пробуем следующий"
        429 -> "$READER: слишком часто (429) — пробуем следующий"
        401, 403 -> "$READER: ключ не принят ($code)"
        else -> "$READER: сервис отказал (код $code) — пробуем следующий"
    }

    private companion object {
        const val READER = "llamaparse"
        const val ID_SPACE = "lp"
        const val API_VERSION = "parse/v2"
        const val DEFAULT_BASE_URL = "https://api.cloud.llamaindex.ai"

        const val DEFAULT_TIER = "cost_effective"

        val LANGUAGES = listOf("ru", "en")
        const val MAX_POLLS = 40
        const val POLL_MS = 1_500L
    }
}

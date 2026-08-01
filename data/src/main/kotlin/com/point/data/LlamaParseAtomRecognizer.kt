package com.point.data

import com.point.core.flow.Atom
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.model.PointObject
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * LlamaParse (LlamaCloud) — второй бесплатный читатель страницы (~10 000 кредитов/мес, ключ без
 * привязки карты). Отдаёт страницу разобранной на элементы с рамками, а не плоским текстом.
 *
 * Контракт запроса (проверено по документации на 2026-08), три шага, потому что API асинхронный:
 * 1. `POST {base}/api/v2/parse/upload` — `multipart/form-data` с полем `file` и полем
 *    `configuration` (JSON-строка), заголовок `Authorization: Bearer …`; в ответе `id` задачи;
 * 2. `GET {base}/api/v2/parse/{id}?expand=items` — пока `job.status` не станет `COMPLETED`;
 * 3. в ответе `items.pages[]`: `page_number`, `page_width`, `page_height` и `items[]` с `bbox`
 *    (`{x,y,w,h}` в системе страницы) и текстом в `value`/`md`.
 *
 * Ожидание — это `delay` между опросами, а не блокировка потока, и живёт оно **после явного тапа**:
 * сетевой слой не имеет права оказаться на первом экране (`CapabilityMeta.network`).
 *
 * Разбор рамки нарочно терпим к трём написаниям (`bbox` объектом, `bbox` массивом спанов, старое
 * `bBox`): поле у сервиса за год переезжало, а фикстура, снятая под одно написание, дала бы
 * зелёный тест при мёртвой геометрии в бою. Терпимость здесь — к **форме поля**, не к отсутствию
 * координат: элемент без рамки в атомы не попадает.
 */
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
                // Самый дешёвый тариф по кредитам: тезис проекта — жить на бесплатном,
                // а не тратить месячную квоту на один кадр.
                FormPart.Field("configuration", JSONObject().put("tier", tier).toString()),
            ),
        )
        if (res.code !in 200..299) error(refusal(res.code, res.body))
        val json = res.body.asJson() ?: error("$READER: ответ не разобран — ${res.body.take(200)}")
        val id = json.optString("id").ifBlank { json.optJSONObject("job")?.optString("id").orEmpty() }
        return id.ifBlank { error("$READER: задача не создана — ${res.body.take(200)}") }
    }

    /**
     * Опрос задачи до готовности.
     *
     * Кончились попытки — это **отказ**, а не пустой слой: пустой слой означал бы «прочитал и
     * ничего не нашёл», и человек принял бы наше нетерпение за свойство страницы.
     */
    private suspend fun awaitResult(jobId: String): JSONObject {
        repeat(MAX_POLLS) { attempt ->
            if (attempt > 0) delay(POLL_MS)
            val res = http.get("$root/api/v2/parse/$jobId?expand=items", auth)
            if (res.code !in 200..299) error(refusal(res.code, res.body))
            val json = res.body.asJson() ?: error("$READER: ответ не разобран — ${res.body.take(200)}")
            when (val status = json.optJSONObject("job")?.optString("status")?.uppercase().orEmpty()) {
                "COMPLETED", "SUCCESS" -> return json
                "ERROR", "FAILED", "CANCELLED", "CANCELED" ->
                    error("$READER: задача не выполнена — ${json.optJSONObject("job")?.optString("error_message").orEmpty().take(200)}")
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
                    // Сервис своей уверенности не сообщает — единица и означает «не сообщил».
                    confidence = 1f,
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

    /** Текст элемента: у таблицы своё значение живёт в `md`, у остальных — в `value`. */
    private fun textOf(item: JSONObject): String =
        item.optString("value").trim().ifEmpty { item.optString("md").trim() }

    /** Рамка элемента в сыром кадре; несколько спанов схлопываются в накрывающий прямоугольник. */
    private fun boxOf(item: JSONObject, frame: OutboundFrame, pageWidth: Float, pageHeight: Float): Box? {
        val raw = item.opt("bbox") ?: item.opt("bBox") ?: return null
        val spans = when (raw) {
            is JSONArray -> (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
            is JSONObject -> listOf(raw)
            else -> emptyList()
        }
        val box = spans.mapNotNull { rectOf(it) }.reduceOrNull { a, b -> a.union(b) } ?: return null
        return frame.toRawFrame(box, layoutWidth = pageWidth, layoutHeight = pageHeight)
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

    private fun refusal(code: Int, body: String): String = when (code) {
        402 -> "$READER: бесплатные кредиты кончились (402) — покупать не идём, пробуем следующий"
        429 -> "$READER: слишком часто (429) — пробуем следующий"
        401, 403 -> "$READER: ключ не принят ($code)"
        else -> "$READER HTTP $code: ${body.take(300)}"
    }

    private companion object {
        const val READER = "llamaparse"
        const val ID_SPACE = "lp"
        const val API_VERSION = "parse/v2"
        const val DEFAULT_BASE_URL = "https://api.cloud.llamaindex.ai"

        /** Самый дешёвый по кредитам тариф; сильнее — только за деньги. */
        const val DEFAULT_TIER = "cost_effective"
        const val MAX_POLLS = 40
        const val POLL_MS = 1_500L
    }
}

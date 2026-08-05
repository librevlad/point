package com.point.data

import com.point.core.flow.Atom
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.model.PointObject
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * LlamaParse (LlamaCloud) — второй бесплатный читатель страницы (бесплатный план: 10 000
 * кредитов/мес). Отдаёт страницу разобранной на элементы с рамками, а не плоским текстом.
 *
 * Про карту первоисточник тарифов молчит — в отличие от Unstructured, где написано «No card
 * required». Поэтому здесь обещания «без карты» нет: ключ задаёт человек, а попросят карту —
 * ключ остаётся пустым и слоя просто нет (см. `local.properties.sample`).
 *
 * Контракт запроса сверен с **машинной спекой самого сервиса** (`/api/openapi.json`, 02.08.2026),
 * а не только с текстом документации; три шага, потому что API асинхронный:
 * 1. `POST {base}/api/v2/parse/upload` — `multipart/form-data` с полем `file` и полем
 *    `configuration` (JSON-строка), заголовок `Authorization: Bearer …`; в ответе `id` задачи.
 *    Спека говорит это дословно: «Send the file as a `file` field and parsing configuration as a
 *    `configuration` JSON string field»;
 * 2. `GET {base}/api/v2/parse/{id}?expand=items` — пока `job.status` не станет `COMPLETED`
 *    (весь набор статусов по спеке: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`);
 * 3. в ответе `items.pages[]`: `page_number`, `page_width`, `page_height` и `items[]` с `bbox`
 *    и текстом в `value`/`md`.
 *
 * Ожидание — это `delay` между опросами, а не блокировка потока, и живёт оно **после явного тапа**:
 * сетевой слой не имеет права оказаться на первом экране (`CapabilityMeta.network`).
 *
 * По спеке `bbox` — это **список** `BBox`, и других написаний в контракте нет. Разбор всё же
 * терпит рамку объектом и старое `bBox`: это стоит трёх строк и спасает от молчаливой потери
 * геометрии, если написание опять поедет. Но терпимость здесь — к **форме поля**, не к отсутствию
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
                FormPart.Field("configuration", configuration()),
            ),
        )
        if (res.code !in 200..299) error(refusal(res.code))
        // #541: куски сырого ответа из отказов убраны — они доезжали до экрана человека через
        // [summariseCloudErrors]. Своя фраза сервиса (`error_message` ниже) остаётся: это слова,
        // а не обрезанный по символам JSON.
        val json = res.body.asJson() ?: error("$READER: ответ не разобран — пробуем следующий")
        val id = json.optString("id").ifBlank { json.optJSONObject("job")?.optString("id").orEmpty() }
        return id.ifBlank { error("$READER: задача не создана — пробуем следующий") }
    }

    /**
     * Настройки разбора одной строкой JSON.
     *
     * **Язык здесь обязателен, и это не украшение.** Эталонный кадр — русская ведомость; сервис,
     * которому не сказали языка, читает её латиницей, и мы получили бы вторую кашу вместо второго
     * чтения — ровно тот провал, ради которого весь этот слой и заводился. Соседний ридер язык
     * получал с первого дня (`languages=rus,eng`), а здесь его молча не было.
     *
     * Коды **другие**, чем у соседа, и списывать их друг у друга нельзя: Unstructured берёт коды
     * Tesseract (`rus`, `eng`), а LlamaParse — свой перечень `ParserLanguages`, где то же самое
     * пишется `ru` и `en` (машинная спека сервиса, 02.08.2026). Порядок значащий: первым идёт
     * основной язык страницы.
     *
     * Тариф — самый дешёвый из тех, что вообще видят страницу: тезис проекта — жить на бесплатном,
     * а не тратить месячную квоту на один кадр. Ниже по кредитам только `fast`, но он
     * правило-ориентированный и снимок не читает вовсе.
     */
    private fun configuration(): String = JSONObject()
        .put("tier", tier)
        .put(
            "processing_options",
            JSONObject().put("ocr_parameters", JSONObject().put("languages", JSONArray(LANGUAGES))),
        )
        .toString()

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
            if (res.code !in 200..299) error(refusal(res.code))
            val json = res.body.asJson() ?: error("$READER: ответ не разобран — пробуем следующий")
            when (val status = json.optJSONObject("job")?.optString("status")?.uppercase().orEmpty()) {
                "COMPLETED", "SUCCESS" -> return json
                "ERROR", "FAILED", "CANCELLED", "CANCELED" -> {
                    // Своя фраза сервиса — это слова, а не кусок ответа: её видно человеку и
                    // достают её отдельно от тела, чтобы одно нельзя было принять за другое.
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

    /** Текст элемента: у таблицы своё значение живёт в `md`, у остальных — в `value`. */
    private fun textOf(item: JSONObject): String =
        item.optString("value").trim().ifEmpty { item.optString("md").trim() }

    /** Рамка элемента в сыром кадре; несколько спанов схлопываются в накрывающий прямоугольник. */
    private fun boxOf(item: JSONObject, frame: OutboundFrame, pageWidth: Float, pageHeight: Float): Box? {
        val box = spansOf(item).mapNotNull { rectOf(it) }.reduceOrNull { a, b -> a.union(b) } ?: return null
        return frame.toRawFrame(box, layoutWidth = pageWidth, layoutHeight = pageHeight)
    }

    /**
     * Уверенность элемента — **та, которую назвал сервис**, а не единица «на всякий случай».
     *
     * Первая редакция ставила здесь 1f с комментарием «сервис своей уверенности не сообщает».
     * Сообщает: по машинной спеке у `BBox` есть поле `confidence`, и стандартное скорингование
     * включено по умолчанию. Единица вместо реального 0.4 — это не осторожность, это сглаженная
     * неуверенность: подсказка «сюда идти перечитывать» гасится ровно там, где она нужнее всего,
     * и слой улик начинает врать тем же способом, от которого он лечит (#257).
     *
     * Несколько спанов схлопываются **минимумом**, а не средним: атом надёжен ровно настолько,
     * насколько надёжен его худший кусок, а среднее — это и есть сглаживание. Не сказал сервис
     * ничего — остаётся 1f, и единица тут означает «не сообщил», как и у соседнего ридера.
     */
    private fun confidenceOf(item: JSONObject): Float =
        spansOf(item)
            .filter { it.has("confidence") && !it.isNull("confidence") }
            .map { it.optDouble("confidence", 1.0).toFloat() }
            .minOrNull()
            ?.coerceIn(0f, 1f)
            ?: 1f

    /** Спаны рамки: по спеке — список, но объект и старое `bBox` тоже понимаются. */
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

    /** Тело ответа в отказ не идёт (#541): человеку оно ничего не объясняет, а на экран доезжает. */
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

        /** Самый дешёвый по кредитам тариф из читающих снимок; сильнее — только за деньги. */
        const val DEFAULT_TIER = "cost_effective"

        /**
         * Коды перечня `ParserLanguages` этого сервиса, а НЕ коды Tesseract у соседнего ридера.
         * Русский первым: порядок в контракте значащий, первым идёт основной язык страницы.
         */
        val LANGUAGES = listOf("ru", "en")
        const val MAX_POLLS = 40
        const val POLL_MS = 1_500L
    }
}

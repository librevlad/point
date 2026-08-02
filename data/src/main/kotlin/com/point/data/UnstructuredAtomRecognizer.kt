package com.point.data

import com.point.core.flow.Atom
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.model.PointObject
import org.json.JSONArray
import org.json.JSONObject

/**
 * Unstructured — бесплатный читатель структуры документа (план «Let's Go»: 15 000 страниц/мес,
 * «No card required» — так на странице тарифов, 02.08.2026). Разбирает страницу на элементы
 * (заголовок, абзац, таблица) и при `coordinates=true` отдаёт каждому многоугольник на странице.
 *
 * Контракт запроса сверен с **машинной спекой самого сервиса** (`/general/openapi.json`,
 * 02.08.2026): единственный путь `POST /general/v0/general`, тело — `multipart/form-data` с полем
 * `files` (`format: binary`), `strategy` из перечня с `hi_res`, `coordinates` — булево,
 * `output_format` — `application/json`. Имя заголовка с ключом проверено живым запросом без ключа:
 * с заголовком `unstructured-api-key` сервис отвечает «API key is malformed», без него — «API key
 * is missing», то есть заголовок читается именно этот. **Адрес у аккаунта может быть свой** — на вкладке
 * API Keys показывают ровно тот, что выдан; поэтому он приходит параметром
 * (`UNSTRUCTURED_API_URL`), а зашитый здесь — только запасной. Ответ — **массив** элементов вида
 * ```json
 * [{"type":"Table","element_id":"…","text":"…",
 *   "metadata":{"page_number":1,"detection_class_prob":0.87,
 *     "coordinates":{"points":[[x,y],…],"system":"PixelSpace",
 *                    "layout_width":2048,"layout_height":1536}}}]
 * ```
 *
 * **Атом здесь — блок, а не слово.** Сервис отдаёт элементы страницы, и это законный спан по
 * контракту атома (#256): «токен или спан», а не обязательно слово. Именно поэтому id живут в
 * своём пространстве (`un0`, `un1`…) — сводить облачное чтение с пословным чтением Tesseract
 * можно только по геометрии, и совпадение индексов было бы ложным мостом.
 *
 * Ключ и адрес инжектируются (из `BuildConfig`, в `DataModule`), а не читаются здесь, — как у
 * [GeminiLlmClient]: сборка запроса и разбор ответа обязаны проверяться подделками независимо от
 * того, какие ключи лежат в `local.properties` (#280).
 */
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
                // Без этого поля геометрия не приезжает вовсе — и весь смысл второго чтения теряется.
                FormPart.Field("coordinates", "true"),
                // hi_res — единственная стратегия, у которой есть модель разметки страницы;
                // fast на фото бланка вернул бы пустоту, а не структуру.
                FormPart.Field("strategy", "hi_res"),
                // Список языков — ПОВТОРЁННОЕ поле формы, а не JSON-массив строкой. Это не
                // догадка: в машинной спеке сервиса (`/general/openapi.json`, 02.08.2026) поле
                // объявлено `{"type":"array","items":{"type":"string"}}` внутри тела
                // `multipart/form-data`, а массив в форме по умолчанию едет повторённым полем.
                // Коды — тессерактовые (`rus`), потому что спека прямо отсылает к Tesseract;
                // у соседнего ридера тот же язык называется `ru`, и списывать нельзя.
                FormPart.Field("languages", "rus"),
                FormPart.Field("languages", "eng"),
                FormPart.Field("output_format", "application/json"),
                // НЕ добавляй сюда `chunking_strategy`. В исходниках сервиса есть таблица «что
                // делать с полем при склейке кусков», и `coordinates` с `detection_class_prob`
                // помечены там DROP: со склейкой ответ приедет без геометрии и без уверенности.
                // Атомов не станет вовсе — а пустой слой по нашему же контракту читается как
                // «страница пустая». Тихий провал ровно того сорта, ради которого весь слой улик.
            ),
        )
        if (res.code !in 200..299) error(refusal(res.code, res.body))
        return layerOf(res.body, frame)
    }

    /**
     * Ответ → слой атомов.
     *
     * Элемент **без координат** в атомы не попадает: адрес выдумывать нельзя, а `Box` из нулей —
     * это адрес, ведущий в никуда. Но его текст остаётся в тексте ридера, поэтому ничего не
     * теряется молча — ровно тот третий путь, которым живёт слой улик (#257).
     */
    private fun layerOf(json: String, frame: OutboundFrame): AtomLayer {
        val elements = runCatching { JSONArray(json) }.getOrElse {
            error("$READER: ответ не разобран — ${json.take(200)}")
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
                // Уверенность даёт только hi_res-модель разметки. Нет её — остаётся 1: шкала
                // внутренняя и между ридерами несравнима (ADR-0001), и выдуманное число здесь
                // сказало бы о ридере ровно столько же, сколько единица, но выглядело бы знанием.
                confidence = meta.optDouble("detection_class_prob", 1.0).toFloat().coerceIn(0f, 1f),
                reader = READER,
                readerVersion = API_VERSION,
                // Сервис нумерует страницы с единицы, атом — с нуля.
                page = (meta.optInt("page_number", 1) - 1).coerceAtLeast(0),
            )
        }
        return AtomLayer(
            atoms = atoms,
            // Свой текст сервиса, а не пересборка по полосам: у него есть собственный анализ
            // раскладки, и на многоколоночном бланке он различает колонки лучше нашей склейки.
            readerText = texts.joinToString("\n").ifEmpty { null },
            transform = frame.transform,
        )
    }

    /** Многоугольник элемента → прямоугольник в сыром кадре. */
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

    /** Отказ человеческими словами: 402/429 — это «бесплатное кончилось», а не «сломалось». */
    private fun refusal(code: Int, body: String): String = when (code) {
        402 -> "$READER: бесплатный лимит исчерпан (402) — покупать не идём, пробуем следующий"
        429 -> "$READER: слишком часто (429) — пробуем следующий"
        401, 403 -> "$READER: ключ не принят ($code)"
        else -> "$READER HTTP $code: ${body.take(300)}"
    }

    private companion object {
        const val READER = "unstructured"
        const val ID_SPACE = "un"

        /** Версия **контракта**, а не модели: модель облако не называет, врать про неё нельзя. */
        const val API_VERSION = "general/v0"
        const val DEFAULT_URL = "https://api.unstructuredapp.io/general/v0/general"
    }
}

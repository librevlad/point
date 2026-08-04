package com.point.data

import com.point.core.flow.ReaderPrivacy
import com.point.core.flow.ReaderPromise
import com.point.core.flow.withoutKey
import com.point.core.model.PointObject
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

/**
 * OCR.space — вторая специальная ручка чтения страницы (#490, #493).
 *
 * **Почему он вообще здесь.** Перемер 04.08.2026 (`docs/VISION-MODELS.md`) взял его в замер впервые
 * — и он взял 15 контрольных кусков из 15 **шесть попыток из шести**, на чистом скане и на мятом
 * фото под углом одинаково, за 2 секунды. В прошлую таблицу он не попал не потому, что плох, а
 * потому что его не спросили. Стоит вторым: медленнее Mistral OCR (0,4 с), но такой же надёжный, и
 * его лимит (25 000 страниц в месяц) там, где у соседа 50 запросов в минуту.
 *
 * **Ключ необязателен — как у OVH, и по той же причине.** В их собственных примерах документации
 * стоит демо-ключ `helloworld`, им замер и делался. Это не секрет и не чужая собственность: он
 * опубликован ровно для того, чтобы ручку можно было попробовать не регистрируясь. Поэтому в
 * раздаваемой сборке этот читатель **живой** — человек, только что поставивший Point, получает двух
 * читателей страницы (этого и OVH) без единого действия. Демо-ключ общий на всех, и упереться в его
 * лимит — обычное дело: тогда цепочка честно идёт дальше, а свой бесплатный ключ поднимает потолок
 * до 25 000 страниц.
 *
 * **Контракт сверен с рабочим замером** (`tools/vision/freeprobe.py`), а не с памятью: POST формой
 * `application/x-www-form-urlencoded` на `/parse/image`, поля `apikey`, `OCREngine=3`,
 * `language=rus`, `isTable=true`, `base64Image=data:image/jpeg;base64,…`; ответ —
 * `{"ParsedResults":[{"ParsedText":"…"}],"IsErroredOnProcessing":false}`.
 *
 * **Движок 3 выбран замером, а не по номеру.** Это их «auto»-движок, который сам определяет язык;
 * именно он дал 15/15. Поле `language` при нём избыточно, но безвредно — оставлено ровно таким, как
 * в замере, чтобы код и числа описывали один и тот же запрос.
 *
 * **Про рукопись он не судился ни разу** — сегодня замерена только печать. Не выдавать одно за
 * другое здесь так же важно, как в самой таблице.
 */
class OcrSpaceReader(
    private val http: HttpJson,
    private val frames: OutboundFrames,
    private val apiKey: () -> String,
    private val baseUrl: String,
) : CloudTextReader {

    override val reader = READER

    /**
     * Германия, ЕС — и **молчание про обучение**.
     *
     * У них написано «All uploaded documents are deleted after processing. We do not keep any of
     * your data» и ни слова о том, учатся ли они на присланном. Достроить второе из первого было бы
     * обещанием за чужой сервис, поэтому здесь [ReaderPromise.UNKNOWN]: на строгом уровне «Не
     * учатся на моём» этот читатель молчит, хотя по стране прошёл бы.
     */
    override val privacy = ReaderPrivacy(
        where = "OCR.space (a9t9 software), Германия (ЕС)",
        promise = ReaderPromise.UNKNOWN,
    )

    /** Демо-ключ из их же примеров — читатель есть всегда; свой ключ лишь поднимает потолок. */
    override val configured = true

    private val root: String get() = baseUrl.ifBlank { DEFAULT_URL }.trim()

    override suspend fun read(obj: PointObject): String {
        val key = apiKey().ifBlank { DEMO_KEY }
        val frame = frames.of(obj) ?: error("$READER: кадр не подготовлен — нечего отправлять")
        val form = form(
            "apikey" to key,
            "OCREngine" to ENGINE,
            "language" to LANGUAGE,
            "isTable" to "true",
            "base64Image" to "data:${frame.mime};base64,${base64(frame.bytes)}",
        )
        // Content-Type ставится вызывающим и выигрывает у общего умолчания `HttpJson` (см.
        // `pointHeaders`): у этой ручки нет JSON-двери, она принимает только форму.
        val res = http.post(root, mapOf("Content-Type" to FORM_TYPE), form)
        // Ключ вычёркивается из ВСЕГО, что вернул сервис: текст отказа доходит до экрана и до
        // отчёта о падении, и сервис не обязан заботиться о нашем секрете за нас.
        val body = withoutKey(res.body, key)
        if (res.code !in 200..299) error(refusal(res.code, body))
        return textOf(body)
    }

    /**
     * Ответ → текст страницы.
     *
     * Сервис умеет отказать с кодом 200 — `IsErroredOnProcessing`, — и пропустить это значит выдать
     * человеку пустую страницу вместо «не прочитал». Ровно тот тихий сбой, от которого лечит
     * договор `CloudTextReader.read`: не дошёл — бросай.
     */
    private fun textOf(json: String): String {
        val answer = runCatching { JSONObject(json) }.getOrElse {
            error("$READER: ответ не разобран — ${json.take(200)}")
        }
        if (answer.optBoolean("IsErroredOnProcessing")) error("$READER: ${errorMessage(answer)}")
        val results = answer.optJSONArray("ParsedResults")
            ?: error("$READER: в ответе нет страниц — ${errorMessage(answer)}")
        return (0 until results.length())
            .mapNotNull { results.optJSONObject(it)?.optString("ParsedText")?.trim()?.ifEmpty { null } }
            .joinToString("\n\n")
    }

    /** `ErrorMessage` приходит и строкой, и списком строк — человеку нужна одна фраза. */
    private fun errorMessage(answer: JSONObject): String {
        val message = when (val raw = answer.opt("ErrorMessage")) {
            is JSONArray -> (0 until raw.length()).joinToString("; ") { raw.optString(it) }
            is String -> raw
            else -> ""
        }.trim()
        return message.ifBlank { "чтение не удалось" }.take(300)
    }

    private fun refusal(code: Int, body: String): String = when (code) {
        402 -> "$READER: бесплатный лимит исчерпан (402) — покупать не идём, пробуем следующий"
        // Общий демо-ключ упирается в лимит чаще своего — это ожидаемый исход, а не поломка.
        429 -> "$READER: слишком часто (429) — пробуем следующий"
        401, 403 -> "$READER: ключ не принят ($code)"
        else -> "$READER HTTP $code: ${body.take(300)}"
    }

    private fun form(vararg fields: Pair<String, String>): String =
        fields.joinToString("&") { (name, value) -> "$name=${encode(value)}" }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val READER = "ocr-space"
        const val DEFAULT_URL = "https://api.ocr.space/parse/image"
        const val FORM_TYPE = "application/x-www-form-urlencoded; charset=utf-8"

        /**
         * Опубликованный демо-ключ из документации сервиса — не секрет и потому единственный, что
         * попадает в раздаваемую сборку. Инвариант «ни один секрет не попадает в артефакт» этим не
         * нарушается: секретом называется то, что даёт доступ к чужой квоте или деньгам, а это —
         * приглашение попробовать, напечатанное на их собственной странице.
         */
        const val DEMO_KEY = "helloworld"

        /** Движок выбран замером: 15/15 шесть попыток из шести. Он же сам определяет язык. */
        const val ENGINE = "3"
        const val LANGUAGE = "rus"
    }
}

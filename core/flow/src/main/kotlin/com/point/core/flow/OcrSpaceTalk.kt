package com.point.core.flow

import java.net.URLEncoder
import java.util.Base64

/**
 * Разговор с OCR.space — один на телефон и компьютер (#1255, решение владельца 23.08.2026).
 *
 * Сервис один, ключ один, разбор ответа один — а написан разговор был дважды, в `:data` и в
 * `:desktop`. И уже разошёлся: телефон слал движок «3», выбранный замером
 * (`tools/vision/freeprobe.py`), компьютер — «2», у которого не было ни комментария, ни
 * теста. Один снимок на двух устройствах читался разными движками, и ни один сторож их не
 * сравнивал.
 *
 * Здесь живёт то, что для сервиса одинаково: форма запроса, движок, разбор ответа и слова
 * отказа. На сторонах остаётся доставка байтов и http — они и правда разные: телефон
 * собирает кадр из `Bitmap`, компьютер читает файл с диска.
 */
object OcrSpaceTalk {

    /** Ручка разбора снимка — адрес по умолчанию у обеих сторон. */
    const val DEFAULT_URL = "https://api.ocr.space/parse/image"

    /** Тело запроса — форма; кодировку сервису надо назвать, иначе кириллица приезжает битой. */
    const val FORM_TYPE = "application/x-www-form-urlencoded; charset=utf-8"

    /**
     * Предел сервиса на один снимок: тяжелее он не берёт.
     *
     * Число знают оба устройства, а укладывается в него каждое по-своему: компьютер ужимает
     * копию (`ImageFit`), телефон режет пиксели при сборке кадра (`OutboundFrame`).
     */
    const val MAX_BYTES = 1024L * 1024

    /** Ключ из примеров сервиса: пока своего нет, Point читает этим. */
    private const val DEMO_KEY = "helloworld"

    /**
     * Движок выбран замером — «OCR.space engine 3» в `tools/vision/freeprobe.py`, — а не
     * вкусом, и на телефоне его держит тест. Компьютер слал «2»: неизмеренный, без
     * комментария, без карточки (#1255).
     */
    private const val ENGINE = "3"

    private const val LANGUAGE = "rus"

    /** Свой ключ выигрывает у демо-ключа: потолок поднимает он. */
    fun keyOrDemo(key: String): String = key.ifBlank { DEMO_KEY }

    /** Что уходит наружу — одна форма на оба устройства. */
    fun form(key: String, mime: String, bytes: ByteArray): String = listOf(
        "apikey" to key,
        "OCREngine" to ENGINE,
        "language" to LANGUAGE,
        "isTable" to "true",
        "base64Image" to "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes),
    ).joinToString("&") { (name, value) -> encode(name) + "=" + encode(value) }

    /**
     * Сервис отказал кодом ответа — своё слово наверх (#1225).
     *
     * Слово объявляется здесь, где известно, что произошло: общий перехват у вызывающего
     * иначе накрывает названный отказ собственным «сервис не ответил» — причина ложная, и
     * человек идёт ждать вместо того, чтобы поправить ключ.
     *
     * @param hint что человек может сделать — если может; про своё место ключа каждая
     *   сторона говорит сама.
     */
    fun refuse(code: Int, hint: String? = null): Nothing = ownWords(serviceRefusal(code, hint))

    /**
     * Ответ сервиса → текст страниц.
     *
     * Отказ приходит и с успешным кодом: 200, `IsErroredOnProcessing=true` и английское поле
     * `ErrorMessage` (#1259). Чужой текст остаётся на своём слое — человеку достаётся своё
     * слово.
     */
    fun textOf(body: String): String {
        val answer = parseJson(body) ?: ownWords(UNREADABLE_ANSWER)
        if (answer.bool("IsErroredOnProcessing") == true) {
            ownWords(serviceRefusalInAnswer(errorMessage(answer)))
        }
        val pages = answer.array("ParsedResults")
        if (pages.isEmpty()) ownWords(serviceRefusalInAnswer(errorMessage(answer)))
        return pages
            .mapNotNull { page -> (page as? JsonValue.Obj).str("ParsedText")?.trim()?.ifEmpty { null } }
            .joinToString("\n\n")
    }

    /** Чужой текст отказа: сервис шлёт его строкой или списком строк. Наружу он не идёт. */
    private fun errorMessage(answer: JsonValue?): String {
        val listed = answer.array("ErrorMessage").mapNotNull { (it as? JsonValue.Str)?.value }
        return listed.ifEmpty { listOfNotNull(answer.str("ErrorMessage")) }
            .joinToString("; ")
            .trim()
            .take(MAX_FOREIGN)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    /** Чужую фразу разбирают по признакам — целиком её держать незачем. */
    private const val MAX_FOREIGN = 300
}

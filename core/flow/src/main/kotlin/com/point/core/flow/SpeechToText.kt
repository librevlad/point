package com.point.core.flow

import com.point.core.model.PointObject

/**
 * Расшифровка записи голоса (#223) — **что** движок услышал, независимо от того, кто он.
 *
 * Отдельный контракт, а не режим [TextRecognizer]: у чтения звука другой вход (запись, а не
 * страница), другая единица работы (минуты, а не кадр) и другой исход (тишина — законный
 * ответ, а не отказ). Сложить их в один тип значило бы завести флаг «читай ушами».
 *
 * Сегодня за контрактом стоит облачная реализация (модель, принимающая аудио вложением); шов
 * существует ради завтрашнего офлайнового движка, который встанет сюда, ничего не меняя выше.
 *
 * **Кто не дошёл — бросает.** Нет ключа, нет сети, провайдер отказал — это исключение с
 * человеческим текстом, а не пустая расшифровка: молчание записи и несостоявшееся чтение —
 * две разные новости, и вызывающий обязан различать их (тот же договор, что у [AtomRecognizer]).
 */
interface SpeechToText {
    suspend fun transcribe(obj: PointObject): Transcription

    /**
     * Чего движку не хватает, чтобы слушать **прямо сейчас**, — или `null`, если он готов.
     *
     * Спрашивается на каждый вызов, а не запоминается при сборке графа: ключ человек вводит на
     * экране ключей, и минуту назад его могло не быть. Ровно на этом сломалась расшифровка (#467) —
     * очередь собиралась один раз по ключу СБОРКИ, которого в раздаваемой сборке нет вовсе, и
     * введённый человеком ключ не включал ничего. Поэтому ответ обязан быть дешёвым (чтение
     * настроек, не сеть) и свежим.
     */
    fun missingKey(): SpeechKeyNeed? = null
}

/**
 * Ключ, без которого движок расшифровки не слушает, — и слова, которыми это говорят человеку.
 *
 * Отказ обязан назвать **конкретного** провайдера: «задайте свой ключ» не отвечает на вопрос
 * «чей», а на экране ключей их семь, и для расшифровки годится не любой (#467).
 */
data class SpeechKeyNeed(
    /** Кто не готов и по какому ключу оживёт: «Whisper слушает по ключу Groq». */
    val phrase: String,
    /** id провайдера из [AI_PROVIDERS], чей ключ включает движок; `null` — подойдёт любой. */
    val providerId: String? = null,
)

/**
 * Есть ли кому слушать записи — вопрос **без** работы и без сети.
 *
 * Отдельный контракт от [SpeechToText] намеренно: способность («что можно») видят UI и Capability,
 * а сам движок («как») остаётся за [Realizer]. Так «нужен ключ» видно ДО тапа, а не после минуты
 * ожидания, — и при этом в то, что видит UI, не приезжает ни одна реализация (тот же приём, что у
 * `PcLinks` рядом с `PcTransport`).
 */
fun interface SpeechReadiness {
    /** Чего не хватает движкам. Пустой список — хотя бы один готов слушать. */
    fun missingKeys(): List<SpeechKeyNeed>
}

/**
 * Чего не хватает очереди [engines]. Пустой список — слушать есть кому.
 *
 * Достаточно ОДНОГО готового движка: пока хоть кто-то слышит, «нужен ключ» было бы неправдой.
 * Чистая функция — одно место на два вопроса («показывать ли подсказку до тапа» и «отказывать ли
 * до работы»), и разъехаться они не могут.
 */
fun speechKeyNeeds(engines: List<SpeechToText>): List<SpeechKeyNeed> {
    val needs = engines.map { it.missingKey() }
    return if (needs.isEmpty() || needs.any { it == null }) emptyList() else needs.filterNotNull()
}

/**
 * Отказ «слушать некому» — словами, которые называют провайдера и ведут в настройки.
 *
 * Прежний текст («AI не настроен — задайте свой ключ») называл причину, которую человек не мог
 * устранить: он шёл на экран ключей, вводил ключ Groq — и ничего не менялось (#467). Теперь сказано
 * и **чей** ключ нужен каждому движку, и **куда** идти; сам переход делает экран по
 * [refusalNeedsKey].
 */
fun speechKeyRefusal(needs: List<SpeechKeyNeed>): String =
    if (needs.isEmpty()) NO_SPEECH_ENGINES
    else "Расшифровать некому: " + needs.joinToString(", ") { it.phrase } + ". $KEY_SETTINGS_CALL"

/** Слушать нечем и подсказать нечего — очередь пуста (в собранном приложении такого не бывает). */
const val NO_SPEECH_ENGINES = "Расшифровать некому — ни один движок распознавания речи не настроен"

/** Чем кончилось слушание записи. */
sealed interface Transcription {

    /**
     * Речь услышана. [text] — дословно сказанное; [summary] — короткая суть или пустая строка,
     * если движок сути не дал. Пустая суть **не** заполняется догадкой: выдуманная сводка хуже
     * её отсутствия, потому что неотличима от настоящей.
     */
    data class Heard(val text: String, val summary: String = "") : Transcription

    /** Речи в записи нет — тишина, шум, музыка без слов. Новость про запись, а не сбой движка. */
    data object Silence : Transcription
}

/** Модель не получила файл. Отдельно от [NO_SPEECH_MARKER] намеренно: «я не слышал записи» и
 *  «в записи тишина» — разные новости человеку, и склейка соврала бы в одном из двух случаев. */
const val NO_AUDIO_MARKER = "NO_AUDIO"

/** В записи нет речи. */
const val NO_SPEECH_MARKER = "NO_SPEECH"

/**
 * Промпт расшифровки — **строгий контракт формата**, а не просьба «ответь покрасивее».
 *
 * Суть и дословный текст приходят одним ответом на один запрос: это одно действие человека, а
 * не цепочка (см. `docs/DECISIONS.md`, #223). Разбирать ответ по маркерам, а не угадывать по
 * прозе, — то же правило, по которому живёт `NO_IMAGE` у зрячих моделей: маркер либо стоит на
 * своём месте, либо нет, и «модель ответила уклончиво» не превращается в факт.
 */
const val TRANSCRIBE_PROMPT: String =
    "Расшифруй эту аудиозапись. Отвечай на языке записи, ничего не выдумывай.\n" +
        "Ответ строго в таком виде, без вступлений и пояснений:\n" +
        "СУТЬ: одно-три предложения о главном\n" +
        "РАСШИФРОВКА:\n" +
        "дословный текст записи\n\n" +
        "Если записи нет в запросе или ты её не получил, ответь ровно одним словом: $NO_AUDIO_MARKER\n" +
        "Если запись есть, но речи в ней не слышно, ответь ровно одним словом: $NO_SPEECH_MARKER"

private const val SUMMARY_MARKER = "СУТЬ:"
private const val TEXT_MARKER = "РАСШИФРОВКА:"

/**
 * Разбор ответа модели по маркерам промпта. Чистая функция — судится юнит-тестом.
 *
 * Ответ без маркеров не считается сбоем: расшифровкой становится весь текст, а сути просто нет.
 * Это осознанный компромисс — модель, не удержавшая формат, всё равно принесла человеку слова,
 * и терять их из-за двоеточия было бы хуже, чем остаться без сводки.
 *
 * @throws IllegalStateException когда модель сообщила, что записи не получила ([NO_AUDIO_MARKER]).
 */
fun parseTranscription(raw: String): Transcription {
    val answer = raw.trim()
    if (answer.startsWith(NO_AUDIO_MARKER)) error("Модель не получила запись — расшифровать нечего")
    if (answer.startsWith(NO_SPEECH_MARKER)) return Transcription.Silence
    if (answer.isBlank()) return Transcription.Silence

    val textAt = answer.indexOf(TEXT_MARKER)
    if (textAt < 0) return Transcription.Heard(stripMarker(answer, SUMMARY_MARKER))

    val text = answer.substring(textAt + TEXT_MARKER.length).trim()
    val head = answer.substring(0, textAt).trim()
    val summary = if (head.startsWith(SUMMARY_MARKER)) head.removePrefix(SUMMARY_MARKER).trim() else ""
    // Пустая расшифровка при непустой сути — модель пересказала, но не расшифровала; слов
    // человеку не досталось, и назвать это удачей нельзя.
    if (text.isBlank()) return Transcription.Silence
    return Transcription.Heard(text, summary)
}

private fun stripMarker(answer: String, marker: String): String =
    if (answer.startsWith(marker)) answer.removePrefix(marker).trim() else answer

/** Суть назвать не вышло. Отдельное слово, а не пустой ответ: «не смог» и «промолчал» — разные вещи. */
const val NO_SUMMARY_MARKER = "NO_SUMMARY"

/**
 * Промпт сути — для движка, который **умеет только слушать** (#223).
 *
 * Whisper отдаёт дословный текст и ничего больше, а человеку обещана суть. Договор контракта
 * позволяет пустую [Transcription.Heard.summary], но обещание тогда не выполнено, поэтому суть
 * добирается ОДНИМ дешёвым текстовым запросом уже по расшифровке. Цепочки здесь нет: цепочка — это
 * когда Point сам запускает **следующее действие**; а сколько работы делает одно действие внутри
 * себя — вопрос его собственного договора (то же решение, что и для сути внутри расшифровки).
 *
 * Наружу уезжает **текст, а не запись**: расшифровка уже у нас, и второй раз отправлять голос
 * человека в чужой сервис незачем.
 */
const val SUMMARIZE_PROMPT: String =
    "Ниже расшифровка голосовой записи. Назови её суть одним-тремя предложениями на языке записи - " +
        "о чём она и что от человека хотят.\n" +
        "Ответ - только суть, без вступлений, без обращений, без пересказа целиком.\n" +
        "Если сути не видно, ответь ровно одним словом: $NO_SUMMARY_MARKER\n\n" +
        "Расшифровка:\n"

/**
 * Ответ модели → суть или пустая строка. Чистая функция — судится юнит-тестом.
 *
 * Пустая строка означает «сути нет», и это законный исход: [Transcription.Heard.summary] не
 * заполняется догадкой. Отбрасывается всё, что сутью не является:
 * - прямое «не смог» ([NO_SUMMARY_MARKER]) и пустой ответ;
 * - пересказ **не короче самой расшифровки** — модель, вернувшая тот же текст, ничего не назвала, а
 *   заголовок «Суть» над ним обещал бы человеку работу, которой не было. По этой же причине у
 *   короткой записи («Перезвони до шести») сути не будет вовсе — ей нечего сокращать.
 */
fun parseSummary(raw: String, text: String): String {
    val answer = stripMarker(raw.trim(), SUMMARY_MARKER).trim()
    if (answer.isEmpty()) return ""
    if (answer.startsWith(NO_SUMMARY_MARKER)) return ""
    if (answer.length >= text.trim().length) return ""
    return answer
}

/**
 * Как расшифровка выглядит объектом: суть сверху, дословный текст под ней.
 *
 * Суть — заголовок, а не сноска: ради неё человек и не слушает три минуты. Когда сути нет,
 * раздела нет тоже — пустой заголовок «Суть» обещал бы то, чего в объекте не лежит.
 */
fun transcriptMarkdown(heard: Transcription.Heard): String = buildString {
    if (heard.summary.isNotBlank()) {
        append("## Суть\n\n")
        append(heard.summary.trim())
        append("\n\n")
    }
    append("## Расшифровка\n\n")
    append(heard.text.trim())
    append("\n")
}

/**
 * Тип записи, который модель действительно читает, — или null, если такую запись слушать некому.
 *
 * Одно место на два вопроса: «отправлять ли эту запись» ([LlmClient.canHandle] у Gemini) и
 * «отказывать ли до сети» (реализация [SpeechToText]). Разъехаться они не могут, потому что
 * это одна функция; раньше такие пары жили в двух файлах и расходились молча.
 *
 * Возвращается **канонический** тип для запроса: голосовое приезжает то как `audio/opus`, то как
 * `application/ogg`, то вовсе как `application/octet-stream` с расширением — модель понимает одно
 * имя, и называть отправленные байты нужно им.
 */
fun modelReadableAudio(mime: String, fileName: String? = null): String? {
    val m = mime.lowercase().substringBefore(';').trim()
    val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    return AUDIO_MIME_ALIASES[m] ?: AUDIO_EXT_MIMES[ext]
}

/** Что именно из этого читается — списком, а не «всё, что начинается с audio/»: `amr` и `wma`
 *  моделям не по зубам, и честнее сказать это словами, чем отправить и получить HTTP 400. */
private val AUDIO_MIME_ALIASES: Map<String, String> = mapOf(
    "audio/ogg" to "audio/ogg",
    "audio/opus" to "audio/ogg",
    "audio/vorbis" to "audio/ogg",
    "application/ogg" to "audio/ogg",
    "audio/mpeg" to "audio/mpeg",
    "audio/mp3" to "audio/mpeg",
    "audio/wav" to "audio/wav",
    "audio/x-wav" to "audio/wav",
    "audio/wave" to "audio/wav",
    "audio/vnd.wave" to "audio/wav",
    "audio/flac" to "audio/flac",
    "audio/x-flac" to "audio/flac",
    "audio/aiff" to "audio/aiff",
    "audio/x-aiff" to "audio/aiff",
    "audio/aac" to "audio/aac",
    "audio/mp4" to "audio/mp4",
    "audio/x-m4a" to "audio/mp4",
    "audio/m4a" to "audio/mp4",
)

private val AUDIO_EXT_MIMES: Map<String, String> = mapOf(
    "ogg" to "audio/ogg",
    "oga" to "audio/ogg",
    "opus" to "audio/ogg",
    "mp3" to "audio/mpeg",
    "wav" to "audio/wav",
    "flac" to "audio/flac",
    "aiff" to "audio/aiff",
    "aif" to "audio/aiff",
    "aac" to "audio/aac",
    "m4a" to "audio/mp4",
)

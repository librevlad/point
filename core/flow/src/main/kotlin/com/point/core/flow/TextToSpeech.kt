package com.point.core.flow

/**
 * Прочитать текст вслух и отдать записью (#442).
 *
 * Обратная сторона `SpeechToText`: там речь становилась словами, здесь слова становятся
 * речью. Голос берётся у самого устройства — это бесплатно, работает без сети и потому
 * приватно по умолчанию. Чтецы с подпиской делают ровно это же за деньги.
 *
 * Контракт ничего не знает про Android: сюда приходит текст, отсюда уходит путь к файлу.
 */
interface TextToSpeech {

    /** Есть ли на устройстве голос вообще и на каких языках. Пусто — читать нечем. */
    suspend fun voices(): List<String>

    /**
     * Прочитать текст в файл по пути `into`.
     *
     * `language` — язык текста, как его определил Point. Голос подбирает исполнитель:
     * какие голоса стоят на устройстве, знает только он.
     *
     * `onDeviceOnly` — читать разрешено только голосом, который не выходит в сеть (#924).
     * Голос по умолчанию у системы нередко серверный: короткая фраза проходит незаметно, а
     * длинная статья уезжает на чужой сервер целиком. Режим приватности решает это так же,
     * как всё остальное, уходящее наружу, — не случайная настройка системы.
     */
    suspend fun speak(
        text: String,
        language: String?,
        into: String,
        onDeviceOnly: Boolean = false,
        onPart: suspend (Int, Int) -> Unit = { _, _ -> },
    ): Spoken
}

/** Чем кончилось чтение. Молчаливое «не вышло» хуже названной причины. */
sealed interface Spoken {

    data class Done(val path: String, val mime: String = "audio/wav") : Spoken

    data class Refused(val why: String) : Spoken
}

/** Голоса на устройстве нет вовсе — ставится он в системных настройках, а не в Point. */
const val NO_VOICE_TEXT = "На этом устройстве нет голоса для чтения вслух. " +
    "Его ставят в настройках системы, в разделе синтеза речи."

/**
 * Режим закрыт, а голос на этом языке читает через интернет (#924).
 *
 * Отказ называет выход: офлайновый голос ставится там же, где и все прочие, — в настройках
 * синтеза речи. Без этой строки отказ правдив и бесполезен.
 */
fun noOfflineVoice(language: String): String =
    "Голос для языка «$language» на этом устройстве читает через интернет, а режим приватности " +
        "сейчас наружу не пускает. Офлайновый голос ставят в настройках системы, в разделе " +
        "синтеза речи."

/** Голос есть, но не на языке текста: прочитанное было бы неразборчивым. */
fun noVoiceForLanguage(language: String): String =
    "На этом устройстве нет голоса для языка «$language». " +
    "Голоса ставят в настройках системы, в разделе синтеза речи."

/**
 * На каком языке написан текст.
 *
 * Определять язык по-настоящему здесь нечем и незачем: голосу нужно знать письмо, а не
 * диалект. Кириллица и латиница различаются по буквам, а внутри кириллицы украинский
 * отличают его собственные буквы — их нет ни в русском, ни в болгарском.
 */
fun languageOfText(text: String): String? {
    val letters = text.filter { it.isLetter() }
    if (letters.isEmpty()) return null

    val cyrillic = letters.count { it in 'а'..'я' || it in 'А'..'Я' || it in UKRAINIAN || it == 'ё' || it == 'Ё' }
    if (cyrillic * 2 < letters.length) return "en"
    return if (letters.any { it in UKRAINIAN }) "uk" else "ru"
}

/** Буквы, которые есть только в украинском письме. */
private const val UKRAINIAN = "іїєґІЇЄҐ"

/**
 * Сколько текста уходит за раз.
 *
 * У системного чтеца свой предел на один заход, и длинная статья в него не влезает. Резать
 * надо по границам предложений: разрыв посреди слова слышен как заикание.
 */
fun speechParts(text: String, limit: Int): List<String> {
    require(limit > 0)
    val clean = text.trim()
    if (clean.length <= limit) return if (clean.isEmpty()) emptyList() else listOf(clean)

    val parts = mutableListOf<String>()
    val current = StringBuilder()
    sentences(clean).forEach { sentence ->
        val piece = if (sentence.length <= limit) listOf(sentence) else hardSplit(sentence, limit)
        piece.forEach { one ->
            if (current.isNotEmpty() && current.length + 1 + one.length > limit) {
                parts += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(one)
        }
    }
    if (current.isNotEmpty()) parts += current.toString()
    return parts
}

private fun sentences(text: String): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    text.forEach { ch ->
        current.append(ch)
        if (ch in ".!?…\n" && current.isNotBlank()) {
            out += current.toString().trim()
            current.clear()
        }
    }
    if (current.isNotBlank()) out += current.toString().trim()
    return out.filter { it.isNotEmpty() }
}

/** Предложение длиннее предела — режем по словам, чтобы не рвать слово пополам. */
private fun hardSplit(sentence: String, limit: Int): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    sentence.split(' ').forEach { word ->
        val one = if (word.length <= limit) word else word.chunked(limit).also { chunks ->
            chunks.dropLast(1).forEach { out += it }
        }.last()
        if (current.isNotEmpty() && current.length + 1 + one.length > limit) {
            out += current.toString()
            current.clear()
        }
        if (current.isNotEmpty()) current.append(' ')
        current.append(one)
    }
    if (current.isNotEmpty()) out += current.toString()
    return out
}

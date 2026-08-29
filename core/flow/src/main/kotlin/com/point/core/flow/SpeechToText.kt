package com.point.core.flow

import com.point.core.model.PointObject

interface SpeechToText {
    suspend fun transcribe(obj: PointObject): Transcription

    fun missingKey(): SpeechKeyNeed? = null
}

data class SpeechKeyNeed(

    val phrase: String,

    val providerId: String? = null,
)

fun interface SpeechReadiness {

    fun missingKeys(): List<SpeechKeyNeed>
}

fun speechKeyNeeds(engines: List<SpeechToText>): List<SpeechKeyNeed> {
    val needs = engines.map { it.missingKey() }
    return if (needs.isEmpty() || needs.any { it == null }) emptyList() else needs.filterNotNull()
}

fun speechKeyRefusal(needs: List<SpeechKeyNeed>): String =
    if (needs.isEmpty()) NO_SPEECH_ENGINES
    else "Расшифровать некому: " + needs.joinToString(", ") { it.phrase } + ". $KEY_SETTINGS_CALL"

const val NO_SPEECH_ENGINES = "Расшифровать некому — ни один движок распознавания речи не настроен"

sealed interface Transcription {

    data class Heard(val text: String, val summary: String = "") : Transcription

    data object Silence : Transcription
}

/** Одни слова про пустую запись на всех путях — и когда услышал телефон, и когда сервис (#1053). */
const val NO_SPEECH_HEARD = "В записи не слышно речи"

/**
 * Расшифровано — и слова легли на саму запись, а не в новый объект (#1097).
 *
 * Одна фраза на оба устройства: работа одна, и сказать о ней человеку надо одно и то же,
 * где бы её ни сделали.
 */
const val SPEECH_IS_KNOWLEDGE = "Расшифровано — слова у записи"

/**
 * Что «Расшифровать» обещает человеку до тапа — одна фраза на оба устройства (#1254).
 *
 * Обещание и исход — про одну и ту же работу, и жить порознь они не могут: слова об исходе
 * уже объявлены здесь ([SPEECH_IS_KNOWLEDGE]), а обещание стояло литералом в телефонном и
 * компьютерном файлах — ровно тот случай, ради которого заведена #1254: одно обещание
 * человеку в двух файлах, которые никто не сверяет. Правка формулировки на одной стороне
 * молча оставляла бы вторую с прежними словами.
 */
const val SPEECH_PROMISE = "слова записи · запись уйдёт в сервис"

const val NO_AUDIO_MARKER = "NO_AUDIO"

const val NO_SPEECH_MARKER = "NO_SPEECH"

/**
 * Суть записи ложится подзаголовком тем же ключом, что суть снимка или текста, — и язык у неё
 * тот же, общим правилом (#1036). Слова записи при этом остаются дословными, на языке
 * говорящего: расшифровка — не перевод.
 */
val TRANSCRIBE_PROMPT: String =
    "Расшифруй эту аудиозапись, ничего не выдумывай.\n" +
        "Ответ строго в таком виде, без вступлений и пояснений:\n" +
        "СУТЬ: одно-три предложения о главном\n" +
        "РАСШИФРОВКА:\n" +
        "дословный текст записи, слово в слово на языке говорящего\n\n" +
        answerLanguageRule("СУТЬ", "запись") + "\n" +
        "Если записи нет в запросе или ты её не получил, ответь ровно одним словом: $NO_AUDIO_MARKER\n" +
        "Если запись есть, но речи в ней не слышно, ответь ровно одним словом: $NO_SPEECH_MARKER"

private const val SUMMARY_MARKER = "СУТЬ:"
private const val TEXT_MARKER = "РАСШИФРОВКА:"

fun parseTranscription(raw: String): Transcription {
    val answer = raw.trim()
    if (answer.startsWith(NO_AUDIO_MARKER)) error("Запись не дошла — расшифровывать нечего")
    if (answer.startsWith(NO_SPEECH_MARKER)) return Transcription.Silence
    if (answer.isBlank()) return Transcription.Silence

    val textAt = answer.indexOf(TEXT_MARKER)
    if (textAt < 0) return Transcription.Heard(stripMarker(answer, SUMMARY_MARKER))

    val text = answer.substring(textAt + TEXT_MARKER.length).trim()
    val head = answer.substring(0, textAt).trim()
    val summary = if (head.startsWith(SUMMARY_MARKER)) head.removePrefix(SUMMARY_MARKER).trim() else ""

    if (text.isBlank()) return Transcription.Silence
    return Transcription.Heard(text, summary)
}

private fun stripMarker(answer: String, marker: String): String =
    if (answer.startsWith(marker)) answer.removePrefix(marker).trim() else answer

const val NO_SUMMARY_MARKER = "NO_SUMMARY"

/** Суть, добранная отдельно по расшифровке, подчиняется тому же языковому правилу (#1036). */
val SUMMARIZE_PROMPT: String =
    "Ниже расшифровка голосовой записи. Назови её суть одним-тремя предложениями - " +
        "о чём она и что от человека хотят. " +
        answerLanguageRule("Суть", "запись") + "\n" +
        "Ответ - только суть, без вступлений, без обращений, без пересказа целиком.\n" +
        "Если сути не видно, ответь ровно одним словом: $NO_SUMMARY_MARKER\n\n" +
        "Расшифровка:\n"

fun parseSummary(raw: String, text: String): String {
    val answer = stripMarker(raw.trim(), SUMMARY_MARKER).trim()
    if (answer.isEmpty()) return ""
    if (answer.startsWith(NO_SUMMARY_MARKER)) return ""
    if (answer.length >= text.trim().length) return ""
    return answer
}

/**
 * Что ложится в файл расшифровки (#873).
 *
 * Раньше сюда вклеивалась ещё и суть, разделом перед текстом. На экране она от этого
 * писалась дважды подряд: сначала подписью под заголовком объекта, потом первой строкой
 * карточки — один источник, два пути показа, которые друг о друге не знают.
 *
 * Решение владельца 12.08.2026: «внизу лучше писать полный текст». Суть отвечает на «о чём
 * это» и остаётся знанием объекта — она живёт в metadata и говорится сверху один раз.
 * А файл расшифровки — это расшифровка, и внизу человек читает её целиком.
 */
fun transcriptFileText(heard: Transcription.Heard): String = heard.text.trim() + "\n"

fun modelReadableAudio(mime: String, fileName: String? = null): String? {
    val m = mime.lowercase().substringBefore(';').trim()
    val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    return AUDIO_MIME_ALIASES[m] ?: AUDIO_EXT_MIMES[ext]
}

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

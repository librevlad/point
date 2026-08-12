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

const val NO_AUDIO_MARKER = "NO_AUDIO"

const val NO_SPEECH_MARKER = "NO_SPEECH"

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

const val SUMMARIZE_PROMPT: String =
    "Ниже расшифровка голосовой записи. Назови её суть одним-тремя предложениями на языке записи - " +
        "о чём она и что от человека хотят.\n" +
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

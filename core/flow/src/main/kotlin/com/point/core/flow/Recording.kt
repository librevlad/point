package com.point.core.flow

import kotlin.math.roundToInt

/**
 * Длительность, прочитанная из самой записи (#1123), — знание объекта.
 *
 * Прикидка по размеру и типичному битрейту ниже остаётся запасным путём: она врала в
 * полтора раза там, где точное значение лежало в заголовке файла.
 */
const val META_DURATION_SECONDS = "duration.seconds"

/** Точная длительность словами: «16 сек», «2 мин 05 сек». */
fun exactRecordingLength(seconds: Long): String? {
    if (seconds <= 0) return null
    if (seconds < 60) return "$seconds сек"
    return "%d мин %02d сек".format(seconds / 60, seconds % 60)
}

fun recordingMinutes(mime: String, sizeBytes: Long, fileName: String? = null): Double? {
    if (sizeBytes <= 0L) return null
    val kbps = typicalKbps(mime, fileName) ?: return null
    val seconds = sizeBytes * 8.0 / (kbps * 1000.0)
    return seconds / 60.0
}

fun recordingLength(mime: String, sizeBytes: Long, fileName: String? = null): String? {
    val minutes = recordingMinutes(mime, sizeBytes, fileName) ?: return null
    val seconds = (minutes * 60.0 / 5.0).roundToInt() * 5

    if (seconds < 5) return null

    if (seconds < 60) return "примерно $seconds сек"
    return "примерно ${minutes.roundToInt()} мин"
}

fun listeningStage(mime: String, sizeBytes: Long, fileName: String? = null): String {
    val minutes = recordingMinutes(mime, sizeBytes, fileName)
    if (minutes == null || minutes < LONG_MINUTES) return LISTENING
    val length = recordingLength(mime, sizeBytes, fileName) ?: return LISTENING
    return "$LISTENING — $length, это займёт время"
}

const val LISTENING = "Слушаю запись"

const val LONG_MINUTES = 2.0

private fun typicalKbps(mime: String, fileName: String?): Int? {
    val m = mime.lowercase().substringBefore(';').trim()
    val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    return when {
        m in OGG_MIMES || ext in setOf("ogg", "oga", "opus") -> 24
        m in M4A_MIMES || ext in setOf("m4a", "aac") -> 64
        m in MP3_MIMES || ext == "mp3" -> 128
        m in WAV_MIMES || ext == "wav" -> 1411
        m in FLAC_MIMES || ext == "flac" -> 900
        else -> null
    }
}

private val OGG_MIMES = setOf("audio/ogg", "audio/opus", "audio/vorbis", "application/ogg")
private val M4A_MIMES = setOf("audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac")
private val MP3_MIMES = setOf("audio/mpeg", "audio/mp3")
private val WAV_MIMES = setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")
private val FLAC_MIMES = setOf("audio/flac", "audio/x-flac")

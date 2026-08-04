package com.point.core.flow

import kotlin.math.roundToInt

/**
 * Сколько примерно длится запись (#223) — чтобы не молчать над трёхминутной голосовухой.
 *
 * **Оценка, и названа оценкой.** Настоящую длительность знает только контейнер, а читать его —
 * android-зависимость (`MediaMetadataRetriever`) ради одной строки на экране ожидания. Здесь
 * считается из веса и **типичного** битрейта формата, и этого хватает ровно на то, для чего
 * нужно: сказать «это займёт время», а не поставить таймер.
 *
 * Формат с неизвестным битрейтом даёт `null` — и тогда числа человек не увидит вовсе. Соблазн
 * был подставить «средний» битрейт всем подряд; это ровно та выдуманная точность, которой
 * Point не занимается: на `wav` она ошиблась бы в десять раз.
 */
fun recordingMinutes(mime: String, sizeBytes: Long, fileName: String? = null): Double? {
    if (sizeBytes <= 0L) return null
    val kbps = typicalKbps(mime, fileName) ?: return null
    val seconds = sizeBytes * 8.0 / (kbps * 1000.0)
    return seconds / 60.0
}

/**
 * Длина записи словами — «примерно 40 сек», «примерно 3 мин» — или null, когда честного числа
 * нет (неизвестный битрейт, нулевой вес).
 *
 * **Одно место, где минуты становятся словами** (#459). Раньше эту фразу умел собрать только
 * [listeningStage], то есть звучала она **после** тапа: сеть уже пошла, квота уже тратится, а
 * человек только сейчас узнавал, что записи сорок минут. Теперь ту же строку показывает экран
 * объекта до тапа, и с фразой ожидания она совпадает не случайно, а потому что фраза одна.
 *
 * Секунды округляются до пятёрки, минуты — до целой: точность здесь всё равно оценочная (см.
 * [recordingMinutes]), и «примерно 47 сек» обещало бы измерение, которого не было.
 */
fun recordingLength(mime: String, sizeBytes: Long, fileName: String? = null): String? {
    val minutes = recordingMinutes(mime, sizeBytes, fileName) ?: return null
    val seconds = (minutes * 60.0 / 5.0).roundToInt() * 5
    // Короче пяти секунд оценка по битрейту врёт больше, чем сообщает: молчим.
    if (seconds < 5) return null
    // Округлившаяся до минуты запись названа минутой: «примерно 60 сек» — то же число, но
    // языком, которым о минуте не говорят.
    if (seconds < 60) return "примерно $seconds сек"
    return "примерно ${minutes.roundToInt()} мин"
}

/**
 * Что говорит реализатор перед тем, как уйти в сеть, — или null, когда сказать нечего сверх
 * самого действия.
 *
 * Порог в [LONG_MINUTES] выбран по поводу, ради которого срез и сделан: голосовое на минуту
 * расшифровывается заметно быстрее, чем слушается, и предупреждать там не о чем; запись от двух
 * минут — уже ожидание, которое без слов читается как «зависло».
 */
fun listeningStage(mime: String, sizeBytes: Long, fileName: String? = null): String {
    val minutes = recordingMinutes(mime, sizeBytes, fileName)
    if (minutes == null || minutes < LONG_MINUTES) return LISTENING
    val length = recordingLength(mime, sizeBytes, fileName) ?: return LISTENING
    return "$LISTENING — $length, это займёт время"
}

/** Слова о работе, которая идёт сейчас: слушаю запись. */
const val LISTENING = "Слушаю запись"

/** С какой примерной длины запись считается долгой. */
const val LONG_MINUTES = 2.0

/**
 * Типичный битрейт формата, кбит/с. Голосовые мессенджеров — opus на 16–24; диктофонный m4a —
 * около 64; mp3 из сети — 128; wav без сжатия — 1411 (44.1 кГц, 16 бит, стерео), и именно из-за
 * него один «средний» битрейт был бы враньём.
 */
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

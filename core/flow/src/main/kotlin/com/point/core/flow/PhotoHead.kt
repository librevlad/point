package com.point.core.flow

/**
 * Что снимок записал о себе в заголовок: когда снят, где и как повернуть кадр (#547, #1379).
 *
 * Телефон читает это `ExifInterface`, а у компьютера такого органа нет — и снимок с датой и
 * местом выглядел там немым: ни «Снято», ни «Очистить метаданные». Разбор чистый, без
 * Android и без библиотек: JPEG → APP1 «Exif» → TIFF → три каталога (основной, Exif, GPS).
 * Съёмочная кухня (камера, выдержка) не читается — для человека с объектом это шум.
 */
data class PhotoHead(
    /** Как записала камера: `2026:10:21 15:30:00`; в слова переводит [shotDateLabel]. */
    val shotAt: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** На сколько градусов повернуть кадр, чтобы он стоял: 0, 90, 180 или 270. */
    val uprightDegrees: Int = 0,
)

/** Заголовок снимка или `null` — это не JPEG либо служебных записей в нём нет. */
fun photoHead(bytes: ByteArray): PhotoHead? {
    val tiff = exifSegment(bytes) ?: return null
    return runCatching { TiffReader(bytes, tiff).head() }.getOrNull()
}

/** Сколько байт начала файла нужно, чтобы дочитать заголовок: APP1 не длиннее 64 КБ. */
const val PHOTO_HEAD_BYTES = 256 * 1024

/** Где в JPEG начинается TIFF-часть записи «Exif»; `null`, если её нет до начала кадра. */
private fun exifSegment(bytes: ByteArray): Int? {
    if (bytes.size < 4 || bytes.u8(0) != 0xFF || bytes.u8(1) != 0xD8) return null
    var at = 2
    while (at + 4 <= bytes.size) {
        if (bytes.u8(at) != 0xFF) return null
        val marker = bytes.u8(at + 1)
        if (marker == 0xFF) {
            at++
            continue
        }
        if (marker == START_OF_SCAN || marker == END_OF_IMAGE) return null
        val length = (bytes.u8(at + 2) shl 8) or bytes.u8(at + 3)
        if (length < 2) return null
        val body = at + 4
        if (marker == APP1 && body + 6 <= bytes.size && EXIF_TAG.indices.all { bytes[body + it] == EXIF_TAG[it] }) {
            return body + EXIF_TAG.size
        }
        at += 2 + length
    }
    return null
}

private class TiffReader(private val bytes: ByteArray, private val base: Int) {

    private val little = when {
        bytes.u8(base) == 'I'.code && bytes.u8(base + 1) == 'I'.code -> true
        bytes.u8(base) == 'M'.code && bytes.u8(base + 1) == 'M'.code -> false
        else -> error("не TIFF")
    }

    fun head(): PhotoHead? {
        val main = entries(u32(base + 4))
        val exif = main[TAG_EXIF_IFD]?.let { entries(it.value) }.orEmpty()
        val gps = main[TAG_GPS_IFD]?.let { entries(it.value) }.orEmpty()

        val shotAt = (exif[TAG_DATE_ORIGINAL] ?: main[TAG_DATE])?.let(::ascii)
        val latitude = coordinate(gps[GPS_LAT], gps[GPS_LAT_REF], negative = 'S')
        val longitude = coordinate(gps[GPS_LON], gps[GPS_LON_REF], negative = 'W')
        val degrees = when (main[TAG_ORIENTATION]?.let { short(it) }) {
            3 -> 180
            6 -> 90
            8 -> 270
            else -> 0
        }
        val head = PhotoHead(
            shotAt = shotAt,
            latitude = latitude.takeIf { longitude != null },
            longitude = longitude.takeIf { latitude != null },
            uprightDegrees = degrees,
        )
        return head.takeIf { it != PhotoHead() }
    }

    private class Entry(val type: Int, val count: Int, val value: Int, val at: Int)

    /** Записи каталога по номеру; кривой каталог даёт пустоту, а не падение всего разбора. */
    private fun entries(offset: Int): Map<Int, Entry> {
        val start = base + offset
        if (offset <= 0 || start + 2 > bytes.size) return emptyMap()
        val count = u16(start)
        return (0 until count).mapNotNull { i ->
            val at = start + 2 + i * 12
            if (at + 12 > bytes.size) return@mapNotNull null
            u16(at) to Entry(type = u16(at + 2), count = u32(at + 4), value = u32(at + 8), at = at + 8)
        }.toMap()
    }

    /** Где лежат данные записи: короче четырёх байт — прямо в ней, иначе по смещению. */
    private fun dataAt(entry: Entry, size: Int): Int = if (size <= 4) entry.at else base + entry.value

    private fun ascii(entry: Entry): String? {
        if (entry.type != TYPE_ASCII || entry.count <= 0) return null
        val from = dataAt(entry, entry.count)
        if (from < 0 || from + entry.count > bytes.size) return null
        return String(bytes, from, entry.count, Charsets.US_ASCII).trimEnd('\u0000').trim().takeIf { it.isNotEmpty() }
    }

    private fun short(entry: Entry): Int? = if (entry.type == TYPE_SHORT) u16(entry.at) else null

    /** Градусы, минуты, секунды тремя дробями → одно число со знаком полушария. */
    private fun coordinate(value: Entry?, ref: Entry?, negative: Char): Double? {
        if (value == null || value.type != TYPE_RATIONAL || value.count < 3) return null
        val from = dataAt(value, 24)
        if (from < 0 || from + 24 > bytes.size) return null
        val parts = (0 until 3).map { i ->
            val denominator = u32(from + i * 8 + 4).toLong() and 0xFFFFFFFFL
            if (denominator == 0L) return null
            (u32(from + i * 8).toLong() and 0xFFFFFFFFL).toDouble() / denominator
        }
        val degrees = parts[0] + parts[1] / 60 + parts[2] / 3600
        val sign = if (ref?.let(::ascii)?.firstOrNull()?.uppercaseChar() == negative) -1 else 1
        return (sign * degrees).takeIf { kotlin.math.abs(it) <= 180 }
    }

    private fun u16(at: Int): Int =
        if (little) bytes.u8(at) or (bytes.u8(at + 1) shl 8) else (bytes.u8(at) shl 8) or bytes.u8(at + 1)

    private fun u32(at: Int): Int =
        if (little) {
            bytes.u8(at) or (bytes.u8(at + 1) shl 8) or (bytes.u8(at + 2) shl 16) or (bytes.u8(at + 3) shl 24)
        } else {
            (bytes.u8(at) shl 24) or (bytes.u8(at + 1) shl 16) or (bytes.u8(at + 2) shl 8) or bytes.u8(at + 3)
        }
}

private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

private const val APP1 = 0xE1
private const val START_OF_SCAN = 0xDA
private const val END_OF_IMAGE = 0xD9
private val EXIF_TAG = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)

private const val TYPE_ASCII = 2
private const val TYPE_SHORT = 3
private const val TYPE_RATIONAL = 5

private const val TAG_ORIENTATION = 0x0112
private const val TAG_DATE = 0x0132
private const val TAG_EXIF_IFD = 0x8769
private const val TAG_GPS_IFD = 0x8825
private const val TAG_DATE_ORIGINAL = 0x9003

private const val GPS_LAT_REF = 1
private const val GPS_LAT = 2
private const val GPS_LON_REF = 3
private const val GPS_LON = 4

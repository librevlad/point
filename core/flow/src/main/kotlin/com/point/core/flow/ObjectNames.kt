package com.point.core.flow

private const val NAME_LIMIT = 40

private const val UNNAMED_TEXT = "Текст"

fun textObjectName(text: String, limit: Int = NAME_LIMIT): String {

    val head = text.take(limit * 4).replace(UNSAFE, " ").replace(SPACES, " ").trim()
    if (head.isEmpty()) return UNNAMED_TEXT
    if (head.length <= limit) return head.trimEnd(*TRAILING).ifBlank { UNNAMED_TEXT }
    val cut = head.take(limit + 1)
    val lastSpace = cut.lastIndexOf(' ')
    val words = (if (lastSpace > limit / 2) cut.take(lastSpace) else head.take(limit)).trimEnd(*TRAILING)
    return if (words.isBlank()) UNNAMED_TEXT else "$words…"
}

fun stampedObjectName(
    what: String,
    epochMillis: Long,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): String = "$what, ${stampLabel(epochMillis, zone)}"

fun looksMachineName(name: String?): Boolean {
    val base = (name ?: return true).substringBeforeLast('.').trim()
    if (base.isEmpty()) return true

    val parts = SEPARATORS.replace(base, " ")

    // Короткое имя — тоже имя (#1049, #1045): `qr.png`, `cv.png`, `id.png` человек назвал
    // сам, и требование «слово от трёх букв» выбрасывало их в «Изображение, 16 авг».
    // Машинное — это то, где после чистки служебных слов не остаётся ни одной буквы:
    // `IMG_1234`, `20260815_093208`, `shared-17553…`.
    return !WORD.containsMatchIn(MACHINE_PREFIX.replace(parts, " "))
}

private val SEPARATORS = Regex("""[_\-.()\[\]]+""")

private val MACHINE_PREFIX =
    Regex("""(?i)\b(shared|record|shot|img|image|photo|screenshot|scr|doc|file|tmp|point)\b""")

private val WORD = Regex("""\p{L}""")

/**
 * Имя человеку — не путь на диске (#937).
 *
 * Раньше отсюда вычищались знаки, запрещённые файловым системам, и присланная ссылка
 * `https://point.leerio.app/privacy` становилась именем `https point.leerio.app privacy`:
 * человек видел изувеченную ссылку вместо ссылки. Дата `12/03/2026` рассыпалась так же.
 *
 * Путь строится не отсюда: имя, из которого делают файл, проходит через [safeFileName] —
 * это его работа и его правило (#865). Здесь остаются только управляющие символы: они не
 * знак, а поломанный экран.
 */
private val UNSAFE = Regex("""\p{Cntrl}""")

private val SPACES = Regex("""\s+""")

private val TRAILING = charArrayOf(' ', ',', '.', ';', '!', '?', '-', '–', '—', '…')

/**
 * Имя, под которым объект уходит наружу: в файл ссылки, в системный лист, в письмо (#1146).
 *
 * Правило одно на все выходы: экранная обрезка («…») в настоящее имя не попадает, путь и
 * идентификатор — тем более, а расширение достраивается по типу, когда его нет, — адресат
 * не должен получать «объект» без рода и племени (#1111, #1126).
 */
fun outboundFileName(name: String?, mime: String): String {
    val ext = extensionForMime(mime)
    val fallback = if (ext.isBlank()) "объект" else "объект.$ext"
    val base = safeFileName(name?.takeIf { it.isNotBlank() } ?: return fallback, ifBlank = "объект")
    if (base.contains('.') && base.substringAfterLast('.').length in 1..5) return base
    return if (ext.isBlank()) base else "$base.$ext"
}

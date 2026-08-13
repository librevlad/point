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

    return !WORD.containsMatchIn(MACHINE_PREFIX.replace(parts, " "))
}

private val SEPARATORS = Regex("""[_\-.()\[\]]+""")

private val MACHINE_PREFIX =
    Regex("""(?i)\b(shared|record|shot|img|image|photo|screenshot|scr|doc|file|tmp|point)\b""")

private val WORD = Regex("""\p{L}{3,}""")

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

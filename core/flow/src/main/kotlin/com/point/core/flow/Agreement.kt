package com.point.core.flow

data class Agreement(

    val value: String,

    val agreed: Boolean,

    val candidates: List<String>,
)

fun normConsensus(s: String): String {
    val bare = s.replace("⚠", "").replace("~~", "").trim()
    return numberFold(bare) ?: bare.lowercase().replace(FORMAT_NOISE, "")
}

private val FORMAT_NOISE = Regex("""[\s\-–—.,«»„“”"'‘’]+""")

private val GROUP_SPACE = Regex("""(?<=\d)[\s\u00A0]+(?=\d)""")

private val NUMBER = Regex("""-?\d+([.,]\d+)*""")

private fun numberFold(s: String): String? {
    val bare = s.replace(GROUP_SPACE, "")
    if (!NUMBER.matches(bare)) return null
    val cut = bare.lastIndexOfAny(charArrayOf('.', ','))
    if (cut < 0) return bare
    return bare.take(cut).replace(".", "").replace(",", "") + "." + bare.substring(cut + 1)
}

fun trimReadingNoise(s: String): String = s.trim()
    .split(NOISE_SPLIT)
    .joinToString(" ") { token -> token.trim { !it.isLetterOrDigit() } }
    .trim()

private val NOISE_SPLIT = Regex("""\s+""")

fun differsOnlyInNoise(a: String, b: String): Boolean {
    val x = trimReadingNoise(a)
    val y = trimReadingNoise(b)
    if (x.isEmpty() || y.isEmpty()) return false
    return normConsensus(x) == normConsensus(y)
}

fun cleanerReading(known: String, fresh: String): String =
    if (readingNoise(known) == 0) known else fresh

private fun readingNoise(s: String): Int = s.trim().length - trimReadingNoise(s).length

fun differsOnlyInAlphabet(a: String, b: String): Boolean =
    a != b && normConsensus(alphabetFold(a)) == normConsensus(alphabetFold(b))

private fun alphabetFold(s: String): String = s.map { HOMOGLYPH[it] ?: it }.joinToString("")

private val HOMOGLYPH: Map<Char, Char> = mapOf(
    'А' to 'A', 'В' to 'B', 'Е' to 'E', 'І' to 'I', 'Ј' to 'J', 'К' to 'K', 'М' to 'M', 'Н' to 'H',
    'О' to 'O', 'Р' to 'P', 'С' to 'C', 'Т' to 'T', 'У' to 'Y', 'Х' to 'X', 'Ѕ' to 'S',
    'а' to 'a', 'е' to 'e', 'і' to 'i', 'ј' to 'j', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'у' to 'y',
    'х' to 'x', 'ѕ' to 's',
)

fun digitsOf(s: String): String = s.filter(Char::isDigit)

fun agree(readings: List<String>): Agreement? {
    val present = readings.map { it.trim() }.filter { it.isNotBlank() }
    if (present.isEmpty()) return null
    val content = present.filter { it.replace("⚠", "").isNotBlank() }
    if (content.isEmpty()) return Agreement(present.first(), agreed = true, candidates = emptyList())

    val byNorm = content.groupBy(::normConsensus)
    val top = byNorm.maxByOrNull { it.value.size }!!
    val pick = top.value.firstOrNull { !it.contains('⚠') } ?: top.value.first()
    return if (byNorm.size == 1) {
        Agreement(pick, agreed = true, candidates = emptyList())
    } else {
        Agreement(pick, agreed = false, candidates = content.distinct())
    }
}

const val META_ALT_SUFFIX = ".alt"

const val META_MORE_SUFFIX = ".more"

fun moreOf(metadata: Map<String, String>, key: String): List<String> =
    metadata[key + META_MORE_SUFFIX]?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

private const val ALT_SEPARATOR = "\n"

fun altValue(readings: List<String>): String = readings.joinToString(ALT_SEPARATOR)

fun alternativesOf(metadata: Map<String, String>, key: String): List<String> =
    metadata[key + META_ALT_SUFFIX]?.split(ALT_SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

fun altLines(value: String): List<String> =
    value.split(ALT_SEPARATOR).map(String::trim).filter { it.isNotBlank() }

const val MAX_REPAIR_RATIO = 0.2

private const val MIN_REPAIRABLE = 8

fun isRepairOf(known: String, fresh: String): Boolean {
    val a = known.trim()
    val b = fresh.trim()
    if (a.length < MIN_REPAIRABLE || b.isEmpty() || a.equals(b, ignoreCase = true)) return false

    val fa = confusableFold(a.lowercase(), b.lowercase())
    val fb = b.lowercase()
    if (fa.filter(Char::isDigit) != fb.filter(Char::isDigit)) return false
    val budget = (maxOf(a.length, b.length) * MAX_REPAIR_RATIO).toInt()
    if (budget < 1) return false
    return editDistance(fa, fb, budget) <= budget
}

private fun confusableFold(s: String, other: String): String {
    val otherTokens = other.split(WORD_TOKEN_SPLIT)
    var index = -1
    return WORD_TOKEN.replace(s) { m ->
        index++
        val token = m.value
        val letters = token.count(Char::isLetter)
        val digits = token.count(Char::isDigit)

        val rival = otherTokens.getOrNull(index).orEmpty()
        if (letters > digits && rival.none(Char::isDigit)) {
            token.map { CONFUSABLE_LETTER[it] ?: it }.joinToString("")
        } else {
            token
        }
    }
}

private val CONFUSABLE_LETTER = mapOf('1' to 'і', '0' to 'о', '3' to 'з')

private val WORD_TOKEN = Regex("""\S+""")
private val WORD_TOKEN_SPLIT = Regex("""\s+""")

private fun editDistance(a: String, b: String, budget: Int): Int {
    if (kotlin.math.abs(a.length - b.length) > budget) return budget + 1
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        var best = cur[0]
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            best = minOf(best, cur[j])
        }
        if (best > budget) return budget + 1
        val swap = prev; prev = cur; cur = swap
    }
    return prev[b.length]
}

/** Отметка улики «этот исполнитель увидел то же значение» (#1176). */
const val AGREE_MARK = "agree:"

/**
 * Согласие независимых исполнителей — улика (#1176, решение владельца: спираль).
 *
 * Без слоя слов у зрячего чтения улик не было вовсе: единственное прочтение и знание,
 * подтверждённое двумя разными моделями, выглядели одинаково. Значение, которое увидели
 * двое и никто не оспорил, получает по отметке на каждого свидетеля — и «посмотрели, но
 * недостаточно» честно становится «нашли». Спорное согласием не считается: спор виден
 * спором (P8). Отметки пересчитываются от списка исполнителей — не копятся вслепую.
 */
fun agreementEvidence(metadata: Map<String, String>, keys: Collection<String>): Map<String, String> =
    keys.asSequence()
        .filterNot { isAnnotationKey(it) || isStateKey(it) }
        .filter { !metadata[it].isNullOrBlank() }
        .filter { alternativesOf(metadata, it).isEmpty() }
        .map { it to actorsOf(metadata, it).distinct() }
        .filter { (_, actors) -> actors.size >= 2 }
        .associate { (key, actors) ->
            val others = metadata[key + META_EVIDENCE_SUFFIX].orEmpty()
                .split(',').map(String::trim)
                .filter { it.isNotBlank() && !it.startsWith(AGREE_MARK) }
            key + META_EVIDENCE_SUFFIX to (others + actors.map { AGREE_MARK + it }).joinToString(",")
        }

fun mergeFacts(
    known: Map<String, String>,
    fresh: Map<String, String>,
    region: String = PhoneNumbers.DEFAULT_REGION,
): Map<String, String> {
    val merged = LinkedHashMap(known)
    fresh.forEach { (key, value) ->

        if (isAnnotationKey(key)) return@forEach
        val was = known[key]
        if (was.isNullOrBlank()) {
            merged[key] = value
            return@forEach
        }

        // Одно знание, записанное по-разному, спором не становится (#932, #1109, #1122):
        // номер в трёх видах, один день с временем и без, адрес с прилипшим соседом. Кто
        // из двух прочтений полнее — видно по ним самим, а не по тому, кто прочитал.
        //
        // Здесь же проигравшее прочтение и пропадает, и там, где тождество ошиблось, вместе
        // с ним пропадает знание: два разных номера, совпавших цифрами, сливаются в один
        // (#1303) — названная цена решения по #1029, а не забытый случай.
        if (sameFact(key, was, value, region)) {
            merged[key] = fullerReading(was, value)
            merged.remove(key + META_ALT_SUFFIX)
            return@forEach
        }

        if (isRepairOf(was, value)) {
            merged[key] = value
            merged.remove(key + META_ALT_SUFFIX)
            return@forEach
        }
        // Вердикт — по ВСЕМ накопленным прочтениям, не по паре (#1176, эксперимент CELL):
        // третий взгляд, совпавший со вторым, раньше не мог победить первое — спор судился
        // «старое против свежего», и большинство не имело голоса. agree() всегда умела
        // список; merge теперь отдаёт ей всю историю. Проигравшее остаётся альтернативой.
        val readings = alternativesOf(known, key).ifEmpty { listOf(was) } + value
        val verdict = agree(readings) ?: return@forEach
        merged[key] = verdict.value
        if (verdict.agreed) {
            merged.remove(key + META_ALT_SUFFIX)
        } else {
            merged[key + META_ALT_SUFFIX] = altValue(verdict.candidates)
        }
    }

    // Принадлежность живёт при значении, как и спор (#1176): вердикт сменил факт — прежнее
    // «чьё» сказано не про него и здесь же снимается. Иначе новый номер стоял бы подписанный
    // старым хозяином во всех путях, где сливаются факты.
    staleBelongings(known, merged).forEach(merged::remove)
    return merged
}

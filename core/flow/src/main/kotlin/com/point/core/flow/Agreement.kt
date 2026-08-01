package com.point.core.flow

/**
 * Several independent readings of the same thing, reconciled into one (#222, шаг 7).
 *
 * Lifted out of [reconcile], which had this logic living inside a table-cell loop. The mechanics
 * are unchanged — plurality after folding away format noise — but a table cell is not the only
 * place two sources read the same thing and disagree. A phone number found by the on-device
 * extractor and again by a model is the same situation with no table in sight.
 *
 * [agreed] false means the sources contradicted each other. That is worth carrying rather than
 * hiding: a value nobody agrees on is not the same as a value everybody agrees on, and a product
 * that shows them identically is lying by omission.
 */
data class Agreement(
    /** The plurality reading, raw — never a normalised or invented form. */
    val value: String,
    /** True when every reading said the same thing, once format noise is folded away. */
    val agreed: Boolean,
    /** The distinct readings when they disagreed; empty when they did not. */
    val candidates: List<String>,
)

/** Fold-away for agreement: case, spacing, dashes, and the ⚠/~~strike~~ markers don't count as a diff.
 *  Public, потому что это общий язык сравнения чтений: якорение кандидатов в `:executors`
 *  обязано сверять ячейки той же свёрткой, что и голосование, — иначе они разойдутся. */
fun normConsensus(s: String): String =
    s.lowercase().replace("⚠", "").replace("~~", "")
        .replace(Regex("""[\s\-–—.,]+"""), "")

/**
 * Votes [readings] of one thing. Null when nothing was read at all — an absent value is not a
 * disagreement, and the caller decides what absence means.
 *
 * Чтение из одних маркеров ⚠ — «не разобрано», а не чтение: содержания у него нет, поэтому оно
 * не перевешивает настоящее значение и не попадает в кандидаты (ревью #258 — «⚠» одной модели
 * побеждало слово, прочитанное со страницы другой, и само становилось вариантом в дропдауне).
 * Если **все** чтения такие — маркер возвращается: «не разобрано» должно пережить голосование,
 * тихая пустота на его месте — та же потеря сигнала.
 *
 * On a tie the **first** reading wins, so the caller controls precedence by ordering: putting
 * what is already known first means a fresh source has to actually outvote it, not merely
 * arrive later.
 *
 * Внутри согласной группы чистое чтение бьёт помеченное независимо от порядка источников:
 * ⚠ переживает голосование, только когда ни один источник не подтвердил значение чистым, —
 * иначе судьба пометки зависела бы от того, кто из источников успел ответить первым (ревью #258).
 */
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

/**
 * Metadata suffix holding the readings a fact was disputed between: `entity.address.alt`.
 *
 * Спор — **канал**, а не число: `DISPUTED_CONFIDENCE = 0.5f` («источники противоречат друг
 * другу») удалён в #264, потому что то же самое уже сказано здесь и уже нарисовано строкой
 * «или: …». Судит спор [isDisputed], а не сравнение с 1f.
 */
const val META_ALT_SUFFIX = ".alt"

/**
 * Metadata suffix для **других значений того же типа на странице**: `entity.track.more`.
 *
 * Не путать с [META_ALT_SUFFIX]: `.alt` — спор источников о чтении ОДНОГО значения, и
 * согласие источников его закрывает ([mergeFacts] снимает ключ). `.more` — на странице
 * реально несколько разных значений (накладная + обратная накладная, v3 §8 «трек найден,
 * но есть второй похожий»), и подтверждение моделью первого номера не имеет права стирать
 * запись о втором (ревью #260 — второй настоящий номер исчезал после «Понять» и мигал,
 * возвращаясь с фоновым ре-OCR). [mergeFacts] этот ключ сознательно не трогает.
 */
const val META_MORE_SUFFIX = ".more"

/** Другие значения того же типа, записанные для [key]; пусто, когда значение одно. */
fun moreOf(metadata: Map<String, String>, key: String): List<String> =
    metadata[key + META_MORE_SUFFIX]?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

/** The readings are separated by newlines — metadata is stored as JSON, which escapes them. */
private const val ALT_SEPARATOR = "\n"

/** The stored form of [alternativesOf]. Exists so exactly one place knows the separator —
 *  a platform line separator here would make the journal unreadable on the other device. */
fun altValue(readings: List<String>): String = readings.joinToString(ALT_SEPARATOR)

/** The alternative readings recorded for [key], or empty when the sources agreed. */
fun alternativesOf(metadata: Map<String, String>, key: String): List<String> =
    metadata[key + META_ALT_SUFFIX]?.split(ALT_SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

/**
 * How far a repair may move a value: a fifth of it. Restoring the `і` OCR ate moves a couple of
 * characters; swapping in a different street moves most of them.
 */
const val MAX_REPAIR_RATIO = 0.2

/** Below this a value is too short for the distance bound to mean anything. */
private const val MIN_REPAIRABLE = 8

/**
 * Is [fresh] the same value as [known], only cleaner (#236)?
 *
 * OCR damages letters — `Олексіївка` comes back as `Олексйвка` — and a model reading the same
 * line can put them back. That is a **repair**, not a contradiction, and treating it as one would
 * either hide the better reading or nag about a difference nobody needs to arbitrate.
 *
 * **Every digit in a number must survive untouched.** Digits carry identity: a phone one digit
 * off is a different person, a waybill one digit off a different parcel, `Хрещатик, 1` против
 * `Хрещатик, 7` — другое здание. Поэтому цифры **цифро-доминантных** токенов неприкосновенны.
 *
 * Но «1ваненко» — не число (#297): это буква «І», убитая OCR в цифру. Одинокая цифра-конфузабл
 * внутри буквенного токена (1↔І, 0↔О, 3↔З — [confusableFold]) — жертва распознавания, и её
 * замена на букву — буквенный ремонт, а не подмена цифры. Цифра буквенного токена, у которой
 * конфузабла нет («Дом7») либо которую меняют на другую цифру («Дом1»→«Дом2»), — по-прежнему
 * identity и по-прежнему спор.
 */
fun isRepairOf(known: String, fresh: String): Boolean {
    val a = known.trim()
    val b = fresh.trim()
    if (a.length < MIN_REPAIRABLE || b.isEmpty() || a.equals(b, ignoreCase = true)) return false
    // Складывается только [known] — то, что прочитал движок: жертвой OCR бывает оно.
    // Свежее чтение цифру не теряет никогда, иначе модель, ДОБАВИВШАЯ цифру («Дом» → «Дом1»),
    // получала бы ремонт за диктовку (ревью #297).
    val fa = confusableFold(a.lowercase(), b.lowercase())
    val fb = b.lowercase()
    if (fa.filter(Char::isDigit) != fb.filter(Char::isDigit)) return false
    val budget = (maxOf(a.length, b.length) * MAX_REPAIR_RATIO).toInt()
    if (budget < 1) return false
    return editDistance(fa, fb, budget) <= budget
}

/**
 * Свёртка OCR-конфузаблов (#297): в **буквенно-доминантном** токене цифры-жертвы канонизируются
 * в свои буквы, и защита цифр их больше не держит. Токены, где цифр не меньше, чем букв, — числа
 * (включая «№9» и одинокую «1») — не трогаются никогда: там цифра несёт identity.
 *
 * Складывается только сторона [known], и только там, где встречное чтение [other] НЕ ставит на
 * место цифры другую цифру. Оба условия — из ревью #297: складывая обе стороны сразу, «Дом1» и
 * «Дом3» теряли цифру одновременно, и identity дома решало расстояние Левенштейна.
 */
private fun confusableFold(s: String, other: String): String {
    val otherTokens = other.split(WORD_TOKEN_SPLIT)
    var index = -1
    return WORD_TOKEN.replace(s) { m ->
        index++
        val token = m.value
        val letters = token.count(Char::isLetter)
        val digits = token.count(Char::isDigit)
        // Складываем цифру-жертву, только когда встречное чтение НЕ ставит на её место цифру:
        // «1ваненко» против «Іваненко» — жертва OCR; «Дом1» против «Дом3» — спор двух цифр,
        // и стереть обе значило бы отдать identity расстоянию Левенштейна (ревью #297).
        val rival = otherTokens.getOrNull(index).orEmpty()
        if (letters > digits && rival.none(Char::isDigit)) {
            token.map { CONFUSABLE_LETTER[it] ?: it }.joinToString("")
        } else {
            token
        }
    }
}

/** Цифра → буква, которую OCR в неё ломает. Список сознательно короткий: пары, живущие в
 *  реальном корпусе кириллицы («1ваненко», «0лена»); расширять — только с дословной фикстурой. */
private val CONFUSABLE_LETTER = mapOf('1' to 'і', '0' to 'о', '3' to 'з')

private val WORD_TOKEN = Regex("""\S+""")
private val WORD_TOKEN_SPLIT = Regex("""\s+""")

/** Levenshtein, abandoned as soon as it exceeds [budget] — the answer above the bound is
 *  «too far», and computing how much too far would be wasted work. */
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

/**
 * Merges freshly read facts into what the object already knew — **by vote, not by overwrite**.
 *
 * Before this, a later source simply won: the deep-understand model's address replaced the
 * on-device extractor's, and nobody could tell whether they had agreed. Now a contradiction is
 * recorded in `<key>.alt`, and what is known keeps precedence on a tie: a paid guess does not
 * get to overrule a local reading just by arriving second.
 */
fun mergeFacts(known: Map<String, String>, fresh: Map<String, String>): Map<String, String> {
    val merged = LinkedHashMap(known)
    fresh.forEach { (key, value) ->
        // Аннотации (`.alt`/`.more`/`.ev`/`.src`) — не факты: ими управляют их авторы,
        // голосование значений их не сливает и не затирает (#261).
        if (isAnnotationKey(key)) return@forEach
        val was = known[key]
        if (was.isNullOrBlank()) {
            merged[key] = value
            return@forEach
        }
        // A cleaner reading of the same thing is not a disagreement (#236). Recording it as one
        // would either hide the better value or ask the user to arbitrate a difference that is
        // simply OCR damage being undone.
        if (isRepairOf(was, value)) {
            merged[key] = value
            merged.remove(key + META_ALT_SUFFIX)
            return@forEach
        }
        val verdict = agree(listOf(was, value)) ?: return@forEach
        merged[key] = verdict.value
        if (verdict.agreed) {
            merged.remove(key + META_ALT_SUFFIX)
        } else {
            // Every reading, winner included — «или это, или то» is the honest shape of a tie.
            val all = (alternativesOf(known, key) + verdict.candidates).distinct()
            merged[key + META_ALT_SUFFIX] = altValue(all)
        }
    }
    return merged
}


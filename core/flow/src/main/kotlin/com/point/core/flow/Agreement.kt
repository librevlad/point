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
 *  обязано сверять ячейки той же свёрткой, что и голосование, — иначе они разойдутся.
 *
 *  **Число складывается как число, а не как строка** (#294). Стирая разделитель наравне с
 *  пробелом, свёртка приравнивала «1,0» к «10»: две модели, прочитавшие ячейку с разницей в
 *  десять раз, объявлялись согласными, и в файл уходило значение без пометки — тихая ошибка
 *  дороже лишнего ⚠. Поэтому [numberFold]: «6,003» и «6.003» — один формат, «20 842» и «20842»
 *  — одно число (разрядный пробел), а пропавшая запятая — другое число и законный спор.
 *  Нули не трогаются вовсе: ведущий ноль барабана счётчика и телефонного номера несущий
 *  (решение зафиксировано в [meterWithoutDrumZeros]). */
fun normConsensus(s: String): String {
    val bare = s.replace("⚠", "").replace("~~", "").trim()
    return numberFold(bare) ?: bare.lowercase().replace(FORMAT_NOISE, "")
}

/**
 * Формат-шум текста: регистр уже снят, а пробелы, тире, точки с запятыми и **кавычки** различием
 * не считаются.
 *
 * Кавычки добавлены по замеру кадра 23: в бланке напечатано `Пластівці вівсяні “Екстра”`
 * типографскими лапками, модель отвечает прямыми — и это уходило в «значение разошлось».
 * Стиль кавычки — оформление, как и разрядный пробел в числе; читателя, который поставил
 * прямую вместо парной, нельзя обвинять в другом чтении.
 */
private val FORMAT_NOISE = Regex("""[\s\-–—.,«»„“”"'‘’]+""")

/** Разрядный пробел — оформление числа, а не его граница: «20 842» и «20842» — одно показание. */
private val GROUP_SPACE = Regex("""(?<=\d)[\s\u00A0]+(?=\d)""")

/** Число целиком: знак, цифры и разделители между ними. Плюс сюда не входит сознательно —
 *  «+380671234567» это телефон, и складывать его как число значило бы потерять «+». */
private val NUMBER = Regex("""-?\d+([.,]\d+)*""")

/**
 * Каноническая форма числа: разрядные пробелы убраны, последний разделитель приведён к точке,
 * прочие (разрядные) стёрты. `null` — это не число, и его складывает общая свёртка.
 *
 * Разделитель последний, а не первый: «1.234,56» и «1 234,56» — одно число, и решает именно
 * правый разделитель. Ячейка с двумя числами («0,72 6,003» — пометка ручкой поверх печати)
 * сюда тоже попадает и складывается устойчиво: она сравнивается только сама с собой.
 */
private fun numberFold(s: String): String? {
    val bare = s.replace(GROUP_SPACE, "")
    if (!NUMBER.matches(bare)) return null
    val cut = bare.lastIndexOfAny(charArrayOf('.', ','))
    if (cut < 0) return bare
    return bare.take(cut).replace(".", "").replace(",", "") + "." + bare.substring(cut + 1)
}

/**
 * Чтение без **шума движка**: у каждого слова срезаны края, не являющиеся ни буквой, ни цифрой.
 *
 * Живой след — ведомость владельца (#493): телефонный движок отдал `[6`, `7,`, `_8.`, `А4152_`,
 * `(31.07.2026`, `солдат'` — и всё это уехало в ячейки Excel. Ни один из этих знаков не является
 * прочитанным символом, это обрамление, дорисованное движком вокруг слова. Отличать обрамление от
 * значения обязана **одна** функция: иначе «7,» и «7.» будут считаться то одним числом, то двумя,
 * смотря кто спрашивает.
 *
 * Срезаются только **края слова**: точка внутри «31.07.2026» и запятая внутри «1,375» — это
 * структура значения, а не шум, и потерять их значило бы получить другое число.
 */
fun trimReadingNoise(s: String): String = s.trim()
    .split(NOISE_SPLIT)
    .joinToString(" ") { token -> token.trim { !it.isLetterOrDigit() } }
    .trim()

private val NOISE_SPLIT = Regex("""\s+""")

/**
 * Одно ли это значение, прочитанное с разным шумом (#493).
 *
 * `true` означает ровно одно: **каждая буква и каждая цифра у двух чтений одна и та же**, разошлись
 * только края слов. Это не «чтения похожи» и не «расстояние мало» — сравнение точное, просто шум
 * движка в него не входит.
 *
 * Зачем отдельно от [normConsensus]: та складывает формат-шум ТЕКСТА (регистр, пробелы, кавычки,
 * разделители числа) и о скобке с подчёркиванием ничего не знает — `_8.` и `8.` для неё разные
 * значения, а `7,` и `7.` — одно. Обе оценки нужны, и отвечают они на разные вопросы.
 */
fun differsOnlyInNoise(a: String, b: String): Boolean {
    val x = trimReadingNoise(a)
    val y = trimReadingNoise(b)
    if (x.isEmpty() || y.isEmpty()) return false
    return normConsensus(x) == normConsensus(y)
}

/**
 * Из двух чтений одного значения — то, чьё **оформление** можно отдать человеку.
 *
 * Правило одно и без арифметики: движок ничего лишнего вокруг слова не дорисовал — символы
 * остаются его, спорить не о чем и менять нечего («20 4514 9154 9395» против «20 4514-9154-9395» —
 * дефис это наш же формат-шум, и переписывать чтение из-за него незачем). Дорисовал — его
 * пунктуации в этой ячейке веры нет, и оформление берёт [fresh].
 *
 * Считать знаки было бы хуже, а не точнее: «[6» и «6.» несут по одному лишнему знаку, но первое —
 * след движка, а второе — номер строки с точкой. Разделяет их не количество, а факт: обрамил ли
 * движок слово вообще.
 *
 * Кому верить в этом случае — не вкусовщина, а замер: печатная кириллица зрячей моделью читается
 * дословно (24/24 на эталонной ведомости, `docs/VISION-MODELS.md`), телефонным движком — с
 * обрамлением почти на каждом слове ведомости владельца.
 */
fun cleanerReading(known: String, fresh: String): String =
    if (readingNoise(known) == 0) known else fresh

/** Сколько знаков чтения — обрамление, а не значение. */
private fun readingNoise(s: String): Int = s.trim().length - trimReadingNoise(s).length

/**
 * Одна ли это надпись, набранная разными алфавитами (#493).
 *
 * `A0998` латиницей и `А0998` кириллицей — не два чтения, а одно: глифы совпадают до пикселя, и
 * движок выбирает алфавит по своему словарю, а не по бумаге. На ведомости владельца такой колонкой
 * был весь номер команды. Считать это спором значило бы поставить ⚠ на десятке ячеек подряд —
 * пометку, которая ничего не сообщает, и заодно подтолкнуть лист к порогу годности.
 *
 * Складываются **только неразличимые глазом** буквы. «Karycra» против «Капуста» так не
 * складывается (`r` и `п` — разные глифы) и остаётся настоящим спором, каким и является.
 */
fun differsOnlyInAlphabet(a: String, b: String): Boolean =
    a != b && normConsensus(alphabetFold(a)) == normConsensus(alphabetFold(b))

/** Кириллические близнецы латинских букв → латиница. Список короткий сознательно: сюда попадает
 *  только то, что на странице выглядит одинаково. */
private fun alphabetFold(s: String): String = s.map { HOMOGLYPH[it] ?: it }.joinToString("")

private val HOMOGLYPH: Map<Char, Char> = mapOf(
    'А' to 'A', 'В' to 'B', 'Е' to 'E', 'І' to 'I', 'Ј' to 'J', 'К' to 'K', 'М' to 'M', 'Н' to 'H',
    'О' to 'O', 'Р' to 'P', 'С' to 'C', 'Т' to 'T', 'У' to 'Y', 'Х' to 'X', 'Ѕ' to 'S',
    'а' to 'a', 'е' to 'e', 'і' to 'i', 'ј' to 'j', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'у' to 'y',
    'х' to 'x', 'ѕ' to 's',
)

/** Цифры чтения подряд — то, что модель не имеет права изменить молча (#258). */
fun digitsOf(s: String): String = s.filter(Char::isDigit)

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


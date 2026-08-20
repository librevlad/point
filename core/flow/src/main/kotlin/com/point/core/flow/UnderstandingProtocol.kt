package com.point.core.flow

/**
 * Протокол понимания: строгий контракт «KEY=значение» между Point и моделью и разбор
 * ответа в кандидатов полей. Один протокол на все поверхности — телефон добавляет к нему
 * слой атомов и судью, компьютер живёт без них; словарь и парсер общие.
 */
val UNDERSTAND_CONTRACT_KEYS: Map<String, String> = mapOf(
    "PHONE" to "phone",
    "EMAIL" to "email",
    "URL" to "url",
    "ADDRESS" to "address",
    "DATE" to "date",
    "CARD" to "card",
    "TRACK" to "track",
    "METER" to "meter",
    "GEO" to "geo",
    "PLACE" to "place",
    "AMOUNT" to "amount",
    "RECEIPT" to "receipt",
    "SUBJECT" to "subject",
)

/** Пара «имя + номер» из строки CONTACT (#653): «в идеале я хочу 3 подписанных контакта». */
data class PersonContact(val name: String, val phone: String)

/**
 * Несколько значений этих видов — несколько объектов, а не спор прочтений одного
 * (#652): второй телефон в переписке — не конфликт первого. Сумма здесь же (#662):
 * разные числа — разные суммы документа («сумма — ещё: 300»), а спор остаётся только
 * неразличимым прочтениям одного числа. Спор по-прежнему у трека, показания, квитанции.
 */
val MULTI_VALUE_FACTS: Set<String> =
    setOf("phone", "email", "url", "card", "date", "amount")
        .mapTo(mutableSetOf()) { META_ENTITY_PREFIX + it }

fun isMultiValueFact(key: String): Boolean = key in MULTI_VALUE_FACTS

/**
 * Прицельная часть витка «Понять сильнее» (#1176, решение владельца: «100% важных вещей
 * корпуса… нужно только правильный подход»).
 *
 * Раньше каждый виток читал объект с чистого листа: модель не знала, зачем её позвали
 * второй раз. Бриф отдаёт ей накопленное знание и открытые вопросы — что уже есть, каких
 * категорий не нашли, что под сомнением и что в споре, — и виток ищет недостающее и
 * проверяет шаткое, а не повторяет сделанное. Пока знания нет, брифа нет: первый виток
 * остаётся чистым взглядом.
 */
fun spiralBrief(metadata: Map<String, String>): String? {
    val known = UNDERSTAND_CONTRACT_KEYS.entries.mapNotNull { (key, suffix) ->
        metadata[META_ENTITY_PREFIX + suffix]?.takeIf(String::isNotBlank)?.let { key to it }
    }
    val summary = metadata[META_SEMANTIC_SUMMARY]?.takeIf(String::isNotBlank)
    val hasCells = metadata.keys.any { cellAddress(it) != null && !isAnnotationKey(it) && !isStateKey(it) }
    if (known.isEmpty() && summary == null && !hasCells) return null

    return buildString {
        append("Этот объект уже разбирали. ")
        summary?.let { append("Суть: ").append(it.take(120)).append(". ") }
        if (known.isNotEmpty()) {
            append("Уже известно:").append('\n')
            known.forEach { (key, value) ->
                append(key).append('=').append(value.take(160)).append('\n')
            }
        }

        val open = UNDERSTAND_CONTRACT_KEYS.entries
            .filter { (_, suffix) -> metadata[META_ENTITY_PREFIX + suffix].isNullOrBlank() }
            .map { it.key }
        if (open.isNotEmpty()) {
            append("Этих категорий пока не найдено: ").append(open.joinToString(", "))
            append(". Поищи их ещё раз внимательно; если на объекте их нет — не пиши строку.")
            append('\n')
        }

        known.forEach { (key, _) ->
            val metaKey = META_ENTITY_PREFIX + UNDERSTAND_CONTRACT_KEYS.getValue(key)
            val disputed = alternativesOf(metadata, metaKey)
            when {
                disputed.isNotEmpty() -> {
                    append("Прочтения ").append(key).append(" спорят: ")
                    append((listOfNotNull(metadata[metaKey]) + disputed).distinct().joinToString(" | ") { it.take(80) })
                    append(" — перечитай и дай своё чтение этой строкой.").append('\n')
                }
                isAssumption(metadata, metaKey) && provenanceOf(metadata, metaKey) != com.point.core.model.Provenance.HUMAN -> {
                    append("Значение ").append(key).append(" под сомнением — проверь и подтверди или поправь.")
                    append('\n')
                }
            }
        }
        // Вопросные ячейки таблицы (#1176, эксперимент CELL): спорные и сомнительные —
        // прицельно, с адресом; бесспорные в бриф не идут, это шум. Тот же цикл, что у
        // контрактных KEY, но адрес структурный, а не смысловой.
        val cells = metadata.keys
            .filter { cellAddress(it) != null && !isAnnotationKey(it) && !isStateKey(it) }
            .filter { !metadata[it].isNullOrBlank() }
            .sorted()
        var asked = false
        cells.forEach { key ->
            val (row, col) = cellAddress(key) ?: return@forEach
            val disputed = alternativesOf(metadata, key)
            when {
                disputed.isNotEmpty() -> {
                    asked = true
                    append("Ячейка таблицы (строка $row, колонка $col) — прочтения спорят: ")
                    append((listOfNotNull(metadata[key]) + disputed).distinct().joinToString(" | ") { it.take(60) })
                    append(". Посмотри на снимок и ответь строкой CELL r$row c$col = точное содержимое.")
                    append('\n')
                }
                isAssumption(metadata, key) &&
                    provenanceOf(metadata, key) != com.point.core.model.Provenance.HUMAN -> {
                    asked = true
                    // Слепая перепроверка: показанное в брифе значение модель охотно
                    // возвращает эхом, и два имени актёров дают ОДНО наблюдение —
                    // согласие с подсказкой уликой не является (RFC Semantic Graph §8,
                    // живой прогон 20.08: openrouter «подтвердил» все восемь сдвинутых
                    // ячеек). Спорная ячейка — другое дело: там оба прочтения показаны,
                    // и модель судит между ними по снимку.
                    append("Ячейка таблицы (строка $row, колонка $col) прочитана неуверенно — ")
                    append("прочти её по снимку с нуля и ответь CELL r$row c$col = содержимое.")
                    append('\n')
                }
            }
        }
        if (asked) {
            append("Строки таблицы считай сверху вниз с 1, не считая шапки; колонки — по ячейкам шапки слева направо с 1. ")
            append("В CELL-ответе давай содержимое дословно; пустая ячейка — не пиши строку.")
            append('\n')
        }

        append("Уже известное без нужды не переписывай; новое и исправленное давай теми же KEY.")
    }
}

/**
 * Адресуемая ячейка таблицы — первый структурный узел Graph (#1176, эксперимент CELL).
 *
 * Идентичность ячейки — сам ключ: `cell.r3.c5` и `cell.r3.c6` не сольются по построению,
 * а «78,00» и «78.00» в одном ключе складывает та же `sameFact`, что и у любого факта.
 * Никакого отдельного конвейера: ячейка — обычный факт со спором, уликами, актёрами
 * и согласием.
 */
const val META_CELL_PREFIX = "cell."

fun cellKey(row: Int, col: Int): String = META_CELL_PREFIX + "r" + row + ".c" + col

/** Адрес из ключа ячейки: (строка, колонка) — или null, если ключ не ячейка. */
fun cellAddress(key: String): Pair<Int, Int>? {
    if (!key.startsWith(META_CELL_PREFIX)) return null
    val m = CELL_KEY.matchEntire(key.removePrefix(META_CELL_PREFIX)) ?: return null
    return m.groupValues[1].toInt() to m.groupValues[2].toInt()
}

private val CELL_KEY = Regex("""r(\d+)\.c(\d+)""")

private val CELL_ANSWER = Regex("""(?i)CELL\s+r\s*(\d+)\s*[.,]?\s*c\s*(\d+)""")

/** Числовые KEY, из которых голая карта переезжает к себе (#657, #1176). */
private val MIGRATES_TO_CARD = setOf("track", "receipt", "meter")

/**
 * Голый номер карты: ровно 16 цифр без букв и контрольная сумма Луна сошлась.
 * Полная уверенность (#657): 15-значные и IBAN не трогаются — там уверенности нет.
 */
fun bareCardNumber(value: String): Boolean {
    if (value.any(Char::isLetter)) return false
    val digits = value.filter(Char::isDigit)
    return digits.length == 16 && luhn(digits)
}

fun parseFieldCandidates(answer: String): ParsedUnderstanding {
    val fields = LinkedHashMap<String, MutableList<FieldCandidate>>()
    val single = LinkedHashMap<String, String>()
    val contacts = mutableListOf<PersonContact>()
    val unsure = mutableSetOf<String>()

    fun offerPhone(raw: FieldCandidate) {

        // Модель нередко клеит имя и номер одной строкой PHONE — это пара, а не
        // длинный номер: «НОВІК Владислав +380 93 242 37 59» (живой прогон 2026-08-09).
        val glued = if (raw.person == null) splitPersonPhone(raw.text) else null
        val candidate = when {
            glued != null -> raw.copy(text = glued.phone, person = glued.name)
            else -> raw
        }

        // Номер карты — не телефон (#657): 16 цифр в phone рождали «Сохранить
        // контакт 5169 3351 09…». Карта при этом не выбрасывается, а узнаётся (#1176).
        if (semanticFits(META_ENTITY_PREFIX + "phone", candidate.text) == false) {
            if (bareCardNumber(candidate.text)) {
                val bucket = fields.getOrPut(META_ENTITY_PREFIX + "card") { mutableListOf() }
                if (bucket.none { normConsensus(it.text) == normConsensus(candidate.text) } &&
                    bucket.size < MAX_FIELD_CANDIDATES
                ) {
                    bucket += candidate.copy(person = null)
                }
            }
            return
        }
        candidate.person?.let { contacts += PersonContact(it, candidate.text) }

        val bucket = fields.getOrPut(META_ENTITY_PREFIX + "phone") { mutableListOf() }
        val twin = bucket.indexOfFirst { normConsensus(it.text) == normConsensus(candidate.text) }
        when {
            twin >= 0 && candidate.person != null && bucket[twin].person == null ->
                bucket[twin] = bucket[twin].copy(person = candidate.person)
            twin >= 0 -> Unit
            bucket.size < MAX_FIELD_CANDIDATES -> bucket += candidate
        }
    }

    answer.lineSequence().forEach { raw ->
        val line = raw.trim()
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val key = line.substring(0, eq).trim().uppercase()
        val rest = line.substring(eq + 1).trim()
        if (rest.isEmpty()) return@forEach
        val cell = CELL_ANSWER.matchEntire(key.trim())
        if (cell != null) {
            // Ответ по адресу ячейки: тем же путём одиночного факта — merge, спор,
            // улики и согласие достаются бесплатно (#1176).
            splitCandidate(rest)?.text?.takeIf { it.isNotBlank() && !startsWithRefusal(it) && !saysNothing(it) }
                ?.let { single.putIfAbsent(cellKey(cell.groupValues[1].toInt(), cell.groupValues[2].toInt()), it) }
            return@forEach
        }
        when {
            // «Убрать TYPE вообще» (#663, решение владельца): ярлык от модели —
            // догадка без признаков («Встреча» на переписке об оплате). Суть несёт
            // SUMMARY; документные типы остаются за офлайн-правилами страницы.
            key == "TYPE" -> Unit

            // Сомнение модели — часть ответа (#670): называет уже известные имена полей,
            // чужие молчат. Значение при этом остаётся значением, а не исчезает.
            key == "UNSURE" -> rest.split(',').forEach { name ->
                UNDERSTAND_CONTRACT_KEYS[name.trim().uppercase()]
                    ?.let { unsure += META_ENTITY_PREFIX + it }
            }
            // Метки слов лепятся и к SUMMARY: «…службы [w38 w39]» уходило на экран
            // подзаголовком (живой прогон 2026-08-09) — хвост снимается всегда.
            key == "SUMMARY" -> splitCandidate(rest)?.text?.takeIf { !saysNothing(it) }
                ?.let { single.putIfAbsent(META_SEMANTIC_SUMMARY, it.take(120)) }

            // Метки слов приходят и к CONTACT-строкам: «…| Іваненко [w47 w48]» —
            // хвост снимается до проверки имени, иначе метки браковали пару
            // (журнал обменов, 2026-08-09).
            key == "CONTACT" -> splitCandidate(rest)?.let { c ->
                parseContact(c.text)?.let { (name, phone) ->
                    offerPhone(FieldCandidate(phone, c.ids, person = name))
                }
            }
            else -> UNDERSTAND_CONTRACT_KEYS[key]?.let { rawSuffix ->
                val candidate = splitCandidate(rest) ?: return@forEach

                // Карта узнаётся по себе (#657, вторая часть; #1176): голые 16 цифр с
                // сошедшейся контрольной суммой Луна — платёжная карта, каким бы KEY
                // модель их ни назвала. «5452198100477458» из переписки приходило
                // треком — и карта терялась, хотя число говорит само за себя.
                val suffix = if (rawSuffix in MIGRATES_TO_CARD && bareCardNumber(candidate.text)) "card" else rawSuffix
                val metaKey = META_ENTITY_PREFIX + suffix

                // Отказ-фраза — не значение ни для какого поля (#656).
                if (startsWithRefusal(candidate.text)) return@forEach

                // Относительное слово — не дата (#659); арифметика — не сумма (#662).
                if (suffix == "date" && relativeDayWord(candidate.text)) return@forEach
                if (suffix == "amount" && looksLikeExpression(candidate.text)) return@forEach

                // Ноль — не сумма документа (#662): комиссия-ноль вставала «ещё»-суммой.
                if (suffix == "amount" && zeroAmount(candidate.text)) return@forEach

                // Форма IBAN — не трек: «UA79…» с квитанции становился готовым
                // «Отследить отправление» (живой прогон 2026-08-09).
                if (suffix == "track" && looksLikeIban(candidate.text)) return@forEach

                // «Голое время это никогда не дата, это мусор» (#651): 11:09 из чата
                // становилось «Нашёл дату».
                if (suffix == "date" && bareClock(candidate.text)) return@forEach

                // «Не плодим сущности без полной уверенности» (#657, решение владельца):
                // здесь — безопасная часть. Дата без цифр («[нет даты]») — не дата;
                // адрес сторожит правдоподобие (товарные строки и слова-мешанины
                // алфавитов гаснут). Общий гейт формы НЕ ставится: правила geo/meter
                // беднее жизни — градусные координаты падали первым же тестом.
                // Типизация «номер»/Луна/миграция типов — следующий срез #657.
                if (suffix == "date" && semanticFits(metaKey, candidate.text) == false) return@forEach

                // Трек — это цифры, а не слово: «квитанцію» и «№ 7 36ір…» с кадров прогона
                // становились трек-номерами, и Point предлагал отследить несуществующее.
                if (suffix == "track" && semanticFits(metaKey, candidate.text) == false) return@forEach
                if (suffix == "address" && !plausibleAddress(candidate.text)) return@forEach

                // Дата — не карта (#747): «ВІД: 29.07/12:59» с почтовой наклейки становилось
                // «Нашёл карту», и Point предлагал перевести деньги на счёт, которого нет.
                // Счёт узнаётся по себе: длина номера карты или форма IBAN, — а не по тому,
                // что модель назвала строку картой.
                if (suffix == "card" && semanticFits(metaKey, candidate.text) == false) return@forEach

                // Дата читается тем же правилом, что и на любом другом входе знания (#782):
                // фраза обрезается до дня, интервал даёт два дня, слипшиеся «26.04.2026
                // 26.04.2026» с чека не рождают спор. Обёртка остаётся подписью.
                val pieces = if (suffix == "date") {
                    readDates(candidate.text).map { day ->
                        candidate.copy(text = day, line = candidate.text.trim().takeIf { it != day })
                    }
                } else {
                    listOf(candidate)
                }
                if (suffix == "phone") {
                    pieces.forEach(::offerPhone)
                    return@let
                }
                val bucket = fields.getOrPut(metaKey) { mutableListOf() }
                pieces.forEach { piece ->
                    if (bucket.size < MAX_FIELD_CANDIDATES && bucket.none { it.text == piece.text && it.ids == piece.ids }) {
                        bucket += piece
                    }
                }
            }
        }
    }
    return ParsedUnderstanding(fields, single, contacts.distinct(), unsure)
}

private val PHONE_CHUNK = Regex("""\+?\d[\d\s\-()./]{6,}\d""")

/** Склейка «имя и номер» в одном значении — пара, а не длинный номер (#653). */
fun splitPersonPhone(text: String): PersonContact? {
    val chunk = PHONE_CHUNK.findAll(text).maxByOrNull { it.value.count(Char::isDigit) } ?: return null
    if (chunk.value.count(Char::isDigit) < 7) return null
    val name = text.removeRange(chunk.range)
        .trim { it.isWhitespace() || it in ",|;:-·" }
        .replace(Regex("""\s+"""), " ")
    if (!plausiblePersonName(name)) return null
    return PersonContact(name, chunk.value.trim())
}

/**
 * «Номер | имя» (порядок прощается): сторона с группой цифр — номер, другая — имя.
 * Имя без правдоподобия ([plausiblePersonName]) парой не становится — номер остаётся.
 */
private fun parseContact(rest: String): Pair<String?, String>? {
    val parts = rest.split('|', ';').map(String::trim).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val phone = parts.firstOrNull { it.count(Char::isDigit) >= 7 } ?: return null
    val name = parts.firstOrNull { it != phone && plausiblePersonName(it) }
    return name to phone
}

data class ParsedUnderstanding(
    val fields: Map<String, List<FieldCandidate>>,
    val single: Map<String, String>,

    /** Пары «имя+номер», которые модель связала по тексту (#653). */
    val contacts: List<PersonContact> = emptyList(),

    /**
     * Ключи, которые модель прочитала неуверенно (#670): спорная цифра на барабане
     * счётчика — знание с оговоркой, а не наравне с бесспорным. Молчание сомнением
     * не считается: не сказали — значит уверены.
     */
    val unsure: Set<String> = emptySet(),
)

fun splitCandidate(rest: String): FieldCandidate? {
    if (saysNothing(rest)) return null
    val m = TRAILING_IDS.find(rest)
    if (m != null) {
        val ids = m.groupValues[2].split(',')
            .flatMap { part -> part.trim().split(WHITESPACE) }
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("rule=") }
            .map(::bareIndexId)
        if (ids.isNotEmpty() && ids.all { ID_SHAPED.matches(it) }) {
            val text = m.groupValues[1].trim()
            return if (text.isEmpty() || saysNothing(text)) null else FieldCandidate(text, ids)
        }
    }
    return FieldCandidate(rest)
}

// Контракт требует отвечать NONE на весь документ, но модели пишут «None»/«null»/
// «не найдено» и в отдельные поля — это отсутствие значения, а не значение
// (живой прогон 2026-08-08: семь действий со значением «None»).
private val NO_VALUE = setOf(
    "none", "null", "nil", "n/a", "na", "-", "—", "–",
    "нет", "не найдено", "не найдена", "отсутствует",
    "немає", "не знайдено", "відсутнє", "відсутній",
)

fun saysNothing(text: String): Boolean = text.trim().trim('.').lowercase() in NO_VALUE

private val IBAN_SHAPED = Regex("""[A-Z]{2}\d{2}[A-Z0-9]{11,30}""")

internal fun looksLikeIban(text: String): Boolean =
    IBAN_SHAPED.matches(text.filterNot(Char::isWhitespace).uppercase())

private val TRAILING_IDS = Regex("""^(.*?)\s*\[([^\[\]]+)]$""")
private val ID_SHAPED = Regex("""[A-Za-z]+\d+""")

/**
 * Метка слова страницы: ею модель ссылается на прочитанное, и Point снимает её перед
 * показом человеку.
 *
 * Имя одно на всех читателей. Локальный движок называл слова по-своему — «ppocr-24», —
 * правило снятия такую метку не узнавало, и на экран владельцу уехало «Номер отправления
 * 59 0017 2462 6327 [ppocr-24]» (охота 11.08.2026).
 */
fun atomLabel(index: Int): String = "w" + index

private val WHITESPACE = Regex("""\s+""")

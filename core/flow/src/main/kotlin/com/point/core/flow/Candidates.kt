package com.point.core.flow

/**
 * Кандидаты на поле с уликами — в одном вызове заполнения (#261, design v3 §7).
 *
 * Вопрос модели «этого действительно нет или ты пропустил?» — самоподтверждение, а не проверка:
 * те же пиксели, та же раскладка, та же модель, а наводящая формулировка смещает её к заполнению
 * пустот. Поэтому перечисление кандидатов — **часть контракта ответа**, а не второй проход:
 * модель возвращает до [MAX_FIELD_CANDIDATES] чтений на поле, каждое — с метками слов страницы,
 * которыми она указала. Судит кандидатов код: улики считаются по слою атомов, невозможное
 * блокируется валидатором, победитель выбирается числом **независимых классов** доказательств.
 *
 * Ни один кандидат не выбрасывается молча: проигравшие остаются рядом с победителем в `.alt`
 * (спор о чтении — тот же канал, что у консенсуса), а «предположение» (меньше двух классов
 * улик) видно как предположение.
 */
data class FieldCandidate(
    /** Чтение модели, дословно. */
    val text: String,
    /** Метки слов страницы, которыми модель указала на значение; пусто — диктовка без указания. */
    val ids: List<String> = emptyList(),
)

/** Больше кандидатов — не честность, а шум: три чтения покрывают реальную неоднозначность. */
const val MAX_FIELD_CANDIDATES = 3

/**
 * Классы доказательств (design v3 §4). Поле **подтверждено**, когда совпали минимум
 * [CONFIRMED_CLASSES] независимых класса; одно доказательство — предположение, и оно видно
 * как предположение. Классы сделаны взаимоисключающими по источнику, чтобы одна физическая
 * улика не подтверждала поле дважды:
 *
 * - [SEMANTIC] — значение подходит под тип поля: формный валидатор ([semanticFits]) либо
 *   улика правила ([ruleEvidence]) на атомах значения;
 * - [GEOMETRIC] — подпись-маркер поля стоит **рядом**: в соседнем пробеге той же строки или
 *   строкой выше над значением;
 * - [LEXICAL] — маркер поля стоит **в том же пробеге**, что значение («ТТН 20 4514…» одной
 *   ячейкой). Один и тот же маркер не может быть и соседним, и внутренним — классы не
 *   пересекаются по построению;
 * - [STRUCTURAL] — значение связно на странице: все метки настоящие, атомы не разорваны
 *   пространственно ([ResolvedValue]);
 * - [ARITHMETIC] — сумма сходится; в этом срезе никто его не выдаёт (сумм у полей-сущностей
 *   нет), класс объявлен ради словаря контракта.
 */
enum class EvidenceClass { SEMANTIC, GEOMETRIC, LEXICAL, STRUCTURAL, ARITHMETIC }

/** Минимум независимых классов, при котором поле подтверждено, а не предположение. */
const val CONFIRMED_CLASSES = 2

/**
 * Суффикс метаданных: классы улик победившего кандидата, через запятую —
 * `entity.track.ev = "semantic,geometric"`. Пишется только путём, который улики **считал**:
 * «Понять» с живым слоем либо офлайновое правило, которое само и есть семантическая улика
 * ([trackFacts] — форма совпала, и это ровно один класс, #264). Отсутствие ключа значит «не
 * судили», а не «улик нет».
 */
const val META_EVIDENCE_SUFFIX = ".ev"

/**
 * Суффикс метаданных: происхождение значения (design v3 §8) — `entity.track.src = "ocr"`.
 *
 * Словарь слов — [com.point.core.model.Provenance] (#264): OCR / RULE / MODEL / HUMAN и GIVEN
 * для «никто не читал». Строковых констант рядом с суффиксом больше нет сознательно — два
 * словаря об одном и том же разъезжаются на первой же правке; пишется всегда `Provenance.X.wire`,
 * читается [provenanceOf].
 */
const val META_SOURCE_SUFFIX = ".src"

/**
 * Суффикс метаданных: чтения, отклонённые hard-block-валидатором (контрольная цифра не
 * сошлась) — `entity.track.blocked`. Невозможное не становится значением, но и не исчезает
 * молча (ревью #261): карточка говорит «прочиталось, но контрольная цифра не сошлась»
 * вместо ложного «не нашлось».
 */
const val META_BLOCKED_SUFFIX = ".blocked"

/** Аннотация ли это, а не значение: `.alt`/`.more`/`.ev`/`.src`/`.blocked` живут рядом с фактом
 *  и не участвуют в голосовании фактов ([mergeFacts] их не сливает — ими управляют их авторы). */
fun isAnnotationKey(key: String): Boolean =
    key.endsWith(META_ALT_SUFFIX) || key.endsWith(META_MORE_SUFFIX) ||
        key.endsWith(META_EVIDENCE_SUFFIX) || key.endsWith(META_SOURCE_SUFFIX) ||
        key.endsWith(META_BLOCKED_SUFFIX)

/**
 * Формный валидатор «значение подходит под тип поля» — класс [EvidenceClass.SEMANTIC].
 * `null` — формы у поля нет, и класс просто недостижим, а не провален.
 * Форма свидетельствует, но не решает: непрошедший кандидат живёт дальше, просто без класса.
 */
fun semanticFits(key: String, value: String): Boolean? {
    val digits = value.count(Char::isDigit)
    return when (key) {
        // Пробелы — формат, не суть (как в [s10CheckDigitValid]): заземлённый кандидат
        // собирается из атомов через пробел — «RA 123456785 UA» тот же S10 (ревью #261).
        META_ENTITY_TRACK -> digits in 13..14 || S10_SHAPED.matches(value.trim().uppercase().replace(" ", ""))
        META_ENTITY_PREFIX + "phone" -> digits in 10..13
        META_ENTITY_PREFIX + "email" -> value.contains('@') && value.substringAfter('@').contains('.')
        META_ENTITY_PREFIX + "card" -> digits in 15..19
        META_ENTITY_PREFIX + "date" -> value.any(Char::isDigit) && value.any { it in ".:/-" }
        // Показание — та же ОДНА реализация границы, которой судит офлайновое правило
        // ([meterDigitsFit]): два счётчика формы разъехались бы на первой правке (#262).
        META_ENTITY_METER -> meterDigitsFit(value)
        // Сумма и номер квитанции — тем же порядком: форму судит та же функция, что и правило.
        // У суммы граница отсекает не «слишком дорого», а чужое число (карту, IBAN), к которому
        // прилипла валюта; у номера квитанции — слово вместо номера («Квитанція ОРИГІНАЛ»).
        META_ENTITY_AMOUNT -> amountDigitsFit(value)
        META_ENTITY_RECEIPT -> receiptNumberShaped(value)
        // Координаты — целиком форма правила: пара в границах глобуса. Одна реализация на
        // обоих читателей, поэтому «модель назвала координатами не координаты» видно сразу.
        META_ENTITY_GEO -> geoPoints(value).isNotEmpty()
        // Адрес — единственное поле, где форма умеет сказать «да» и не умеет сказать «нет»
        // (#262). Записей адреса больше, чем правило узнаёт: «м. Павлоград, вул. Кодацька, 39,
        // Днiпропетровська обл.» — настоящий адрес, а под форму правила не подходит. Поэтому
        // узнанная форма даёт класс, а неузнанная оставляет его недостижимым, а не проваленным:
        // иначе честное чтение модели теряло бы голос в [judgeFields] за чужую скупость.
        // Реализация одна на обоих судей — ту же [addressForm] считает офлайновое правило.
        META_ENTITY_ADDRESS -> if (addressForm(value.trim()) != null) true else null
        else -> null
    }
}

/**
 * Улики, которые видно **без страницы**: форма значения и сошедшаяся контрольная цифра (#262).
 *
 * Одна реализация на всех судей — офлайновое правило ([trackFacts]), суд кандидатов модели по
 * слою ([fieldEvidence]) и тот же суд, когда слоя нет вовсе. Два счётчика улик разъехались бы на
 * первой правке, и «правило считает номер подтверждённым, а модель — предположением» стало бы
 * невидимым расхождением.
 *
 * Контрольная цифра — [EvidenceClass.ARITHMETIC], и это **второй, независимый от формы класс**:
 * форма говорит «так пишут номера», арифметика — «эти девять цифр согласованы между собой».
 * Поэтому S10 подтверждён без всякой страницы, а 14-значный номер Новой Почты — нет: у него
 * такого доказательства не существует, и притворяться иначе значило бы врать сильнее.
 *
 * **Контрольная цифра считается только полю, чьей форме она принадлежит** (ревью #262). S10 —
 * стандарт почтового отправления, и «сошлась» доказывает отправление, а не дату и не телефон.
 * Первая версия считала её любому ключу, и `entity.date = "RA123456785UA"` получал класс улик из
 * ниоткуда — а класс улик выбирает победителя среди кандидатов ([judgeFields]), то есть чужое
 * доказательство могло перевесить настоящее чтение. Ключ здесь по той же причине, по которой он
 * есть у [semanticFits] — улика всегда про конкретное поле.
 */
fun formEvidence(key: String, value: String): Set<EvidenceClass> = buildSet {
    if (semanticFits(key, value) == true) add(EvidenceClass.SEMANTIC)
    if (key == META_ENTITY_TRACK && s10CheckDigitValid(value) == true) add(EvidenceClass.ARITHMETIC)
}

/**
 * Контрольная цифра S10 (UPU): `RA123456789UA` — 8 цифр значения, девятая контрольная,
 * веса 8 6 4 2 3 5 9 7, C = 11 − (Σ mod 11), 10 → 0, 11 → 5.
 *
 * `null` — формат не S10, и checksum **неприменима** (у 14-значного номера Нова Пошты
 * опубликованного алгоритма нет — выдумать его значило бы отбрасывать настоящие номера).
 * `false` — hard-block (design v3 §4): «checksum не прошла там, где формат её поддерживает» —
 * математически невозможное значение, единственный законный повод валидатора отклонить.
 */
fun s10CheckDigitValid(value: String): Boolean? {
    val v = value.trim().uppercase().replace(" ", "")
    if (!S10_SHAPED.matches(v)) return null
    val digits = v.substring(2, 11).map { it - '0' }
    val sum = S10_WEIGHTS.indices.sumOf { digits[it] * S10_WEIGHTS[it] }
    val check = when (val c = 11 - sum % 11) {
        10 -> 0
        11 -> 5
        else -> c
    }
    return check == digits[8]
}

private val S10_SHAPED = Regex("""[A-Z]{2}\d{9}[A-Z]{2}""")
private val S10_WEIGHTS = intArrayOf(8, 6, 4, 2, 3, 5, 9, 7)

/**
 * Метка индекса без промпт-атрибутов: скобка показывает `[a2 rule=track-shaped]`, и модель
 * может процитировать её целиком — терять указание из-за нашей же подсказки нельзя (ревью #283).
 * Срезается только собственный синтаксис индекса, чужие id не трогаются.
 */
fun bareIndexId(id: String): String = id.replace(INDEX_ATTRS, "")

private val INDEX_ATTRS = Regex("""\s+rule=\S*$""")

/**
 * Улики кандидата по слою страницы. Считает только то, что можно проверить по атомам;
 * кандидат без меток может заработать разве что [EvidenceClass.SEMANTIC].
 *
 * [ruleMarks] — готовая разметка [ruleEvidence] (считается один раз на вызов, не на кандидата).
 */
fun AtomLayer.fieldEvidence(
    key: String,
    candidate: FieldCandidate,
    ruleMarks: Map<String, List<String>> = ruleEvidence(),
): Set<EvidenceClass> {
    val classes = mutableSetOf<EvidenceClass>()
    val resolved = resolve(AtomAddress.ByIds(candidate.ids.map(::bareIndexId)))

    // SEMANTIC: форма значения — валидатором либо уликой правила на атомах значения.
    // Улика правила требует ПОЛНОГО окна: правило метило пробег с суммой цифр ровно
    // [WAYBILL_DIGITS], и кусок этого пробега — не «похоже на трек», а кусок.
    // ARITHMETIC приходит оттуда же, где его берёт офлайновое правило ([formEvidence], #262).
    classes += formEvidence(key, candidate.text)
    val ruleFits = key == META_ENTITY_TRACK && resolved.atoms.isNotEmpty() &&
        resolved.atoms.all { "track-shaped" in ruleMarks[it.id].orEmpty() } &&
        resolved.text.count(Char::isDigit) == WAYBILL_DIGITS
    if (ruleFits) classes += EvidenceClass.SEMANTIC

    if (resolved.atoms.isEmpty()) return classes
    // STRUCTURAL: связь со страницей не порвана — метки настоящие, набор не разорван.
    if (resolved.droppedIds.isEmpty() && !resolved.disjoint) classes += EvidenceClass.STRUCTURAL

    val markers = FIELD_MARKERS[key].orEmpty()
    if (markers.isEmpty()) return classes
    val valueIds = resolved.atoms.mapTo(mutableSetOf()) { it.id }
    val named = atoms.filter { it.text.isNotBlank() }
    val pageLines = lines(named)
    val lineOf = HashMap<String, Int>()
    pageLines.forEachIndexed { i, line -> line.forEach { lineOf[it.id] = i } }
    val valueLines = resolved.atoms.mapNotNull { lineOf[it.id] }.toSet()
    if (valueLines.isEmpty()) return classes

    fun isMarker(atom: Atom) = atom.text.trim().trimEnd(':', '.').lowercase() in markers

    // LEXICAL: маркер в том же пробеге, что значение («ТТН 20 4514…» одной ячейкой).
    // GEOMETRIC: маркер в СОСЕДНЕМ пробеге той же строки либо строкой выше НАД значением,
    // не дальше [LABEL_GAP_HEIGHTS] высот по вертикали. Смежность и потолок обязательны
    // (ревью #261): «Накладна №» в дальней колонке той же полосы и заголовок через пустые
    // полстраницы — не подпись, а инфляция класса, меняющая победителя.
    val valueBox = resolved.atoms.map { it.box }.reduce(Box::union)
    valueLines.forEach { li ->
        val runs = cellRuns(pageLines[li])
        val valueRunIdx = runs.indices.filter { i -> runs[i].any { it.id in valueIds } }
        runs.forEachIndexed { ri, run ->
            val holdsValue = ri in valueRunIdx
            val marker = run.any { it.id !in valueIds && isMarker(it) }
            if (marker && holdsValue) classes += EvidenceClass.LEXICAL
            if (marker && !holdsValue && valueRunIdx.any { kotlin.math.abs(it - ri) == 1 }) {
                classes += EvidenceClass.GEOMETRIC
            }
        }
        pageLines.getOrNull(li - 1).orEmpty().forEach { atom ->
            val overlapsX = atom.box.left <= valueBox.right && valueBox.left <= atom.box.right
            val closeAbove = valueBox.top - atom.box.bottom <=
                maxOf(atom.box.height, valueBox.height) * LABEL_GAP_HEIGHTS
            if (overlapsX && closeAbove && isMarker(atom)) classes += EvidenceClass.GEOMETRIC
        }
    }
    return classes
}

/** Подпись стоит над значением вплотную; заголовок через пустое поле — не подпись.
 *  Порог в высотах, не в пикселях — страница приходит в любом разрешении. */
private const val LABEL_GAP_HEIGHTS = 2f

/**
 * Слова-маркеры полей — **свидетели, не решатели** (design v3 §4): маркер даёт класс улики
 * рядом с кандидатом, которого уже назвала модель, и никогда не ищет значения сам. Ложный
 * якорь («Кому» внутри цитаты) поэтому стоит одно очко из двух необходимых, а не роль.
 *
 * `internal` — ради инварианта, а не ради доступа: слова трека читает тест, который держит этот
 * словарь и стемы плоского текста ([looksLikeTrackMarker], #262) от расхождения.
 */
internal val FIELD_MARKERS: Map<String, List<String>> = mapOf(
    META_ENTITY_TRACK to listOf(
        "ттн", "трек", "трек-номер", "накладна", "накладная", "відправлення", "отправление",
        "waybill", "tracking",
    ),
    META_ENTITY_PREFIX + "phone" to listOf("тел", "телефон", "phone", "моб", "mobile"),
    META_ENTITY_PREFIX + "email" to listOf("email", "e-mail", "почта", "пошта", "мейл"),
    META_ENTITY_PREFIX + "date" to listOf("дата", "date", "від", "от"),
    META_ENTITY_PREFIX + "address" to listOf(
        "адреса", "адрес", "address", "відділення", "отделение",
    ),
    META_ENTITY_PREFIX + "card" to listOf("карта", "картка", "card", "iban", "рахунок", "счёт", "счет"),
    // Единица учёта — маркер показания наравне с подписью: «кВт·ч» рядом с числом стоит
    // отдельным пробегом, и это ВТОРОЙ, независимый от формы класс улик (позиционный).
    META_ENTITY_METER to listOf(
        "показання", "показания", "показник", "лічильник", "личильник", "счётчик", "счетчик",
        "meter", "reading", "квт·ч", "квт*ч", "квтч", "квт·год", "м³", "м3", "kwh", "гкал",
    ),
    META_ENTITY_GEO to listOf(
        "координати", "координаты", "coordinates", "gps", "широта", "довгота", "долгота",
    ),
    // Знак валюты рядом с числом — маркер суммы наравне с подписью колонки: он стоит отдельным
    // пробегом («500.00 | грн») и потому даёт ВТОРОЙ, независимый от формы класс улик.
    META_ENTITY_AMOUNT to listOf(
        "сума", "сумма", "amount", "total", "всього", "итого", "до сплати", "к оплате",
        "грн", "₴", "uah", "$", "usd", "€", "eur",
    ),
    // Слова квитанции. Инвариант держит тест: то же слово обязано узнаваться плоским
    // правилом ([looksLikeReceiptMarker]) — иначе два судьи одного маркера разъедутся.
    META_ENTITY_RECEIPT to listOf("квитанція", "квитанции", "квитанция", "квитанцію", "receipt"),
    META_ENTITY_SUBJECT to listOf("тема", "subject", "тему"),
)

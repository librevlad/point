package com.point.core.flow

/**
 * Улики офлайн-правил на атомах слоя (#258, design v3 §4): id атома → имена совпавших форм.
 *
 * Правило **размечает токен, не решая его роль** — разрешение живого спора консилиума: словари
 * и геометрия до модели дают ложные якоря («Кому» внутри цитаты), а роль, зафиксированная до
 * контекста, невосстановима. Поэтому улика попадает в разметку входа модели (`rule=track-shaped`
 * в [promptIndex]) и никогда — в отсев или в готовое значение. Одно правило — предположение,
 * и оно видно как предположение.
 *
 * Правила двух видов:
 * - **пробеги**: форма, собранная из соседних слов строки — 14-значный трек приходит тремя
 *   атомами (`20` + `4514 9154` + `9395`), и судить её можно только по склейке; помечается
 *   каждый атом пробега;
 * - **токены**: форма целого слова — `14:32` похоже на время суток, а не на дату документа
 *   (живой случай #244, где время статуса вытесняло настоящую дату).
 *
 * Пробег судится **внутри строки**: разорванный переносом строки номер — не та же улика, и
 * склеивать его до модели значило бы решать за неё.
 */
fun AtomLayer.ruleEvidence(): Map<String, List<String>> {
    val evidence = LinkedHashMap<String, MutableList<String>>()
    fun mark(atom: Atom, rule: String) {
        val rules = evidence.getOrPut(atom.id) { mutableListOf() }
        if (rule !in rules) rules += rule
    }
    lines(atoms.filter { it.text.isNotBlank() }).forEach { line ->
        line.forEach { atom ->
            if (BARE_CLOCK.matches(atom.text.trim())) mark(atom, "clock-shaped")
        }
        // Строка как один текст + карта «какой атом каким диапазоном лёг» — чтобы совпадение
        // регекса вернулось к атомам, из которых собралось.
        val joined = StringBuilder()
        val spans = line.map { atom ->
            if (joined.isNotEmpty()) joined.append(' ')
            val start = joined.length
            joined.append(atom.text)
            atom to (start until joined.length)
        }
        WAYBILL_SHAPED.findAll(joined).forEach { m ->
            // Тот же фильтр, что в [waybillNumbers]: форма структурная, счётчик цифр — её часть.
            if (m.value.count(Char::isDigit) != WAYBILL_DIGITS) return@forEach
            spans.forEach { (atom, span) ->
                if (span.first <= m.range.last && m.range.first <= span.last) mark(atom, "track-shaped")
            }
        }
    }
    return evidence
}

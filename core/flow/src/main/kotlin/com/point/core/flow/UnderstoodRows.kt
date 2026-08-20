package com.point.core.flow

/**
 * Как называется этот вид знания человеку.
 *
 * Рядом жила `understoodRows` — прежняя сборка строк знания, которую с появлением
 * `knowledgeRows` (спор, «ещё», подпись, открытые вопросы) не звал уже никто. Мёртвый
 * код удалён (#840): он выглядел как рабочий путь и приглашал править себя вместо
 * настоящего.
 */
fun understoodName(key: String): String? {
    cellAddress(key)?.let { (row, col) -> return "Ячейка $row×$col" }
    return understoodEntityName(key)
}

private fun understoodEntityName(key: String): String? = when (key.removePrefix(META_ENTITY_PREFIX)) {
    "phone" -> "Телефон"
    "email" -> "Почта"
    "url" -> "Ссылка"
    "address" -> "Адрес"
    "date" -> "Дата"
    "card" -> "Карта"
    "amount" -> "Сумма"
    "track" -> "Накладная"
    "meter" -> "Показание"
    "qr" -> "QR-код"
    "geo" -> "Координаты"
    "place" -> "Место"
    "receipt" -> "Квитанция"
    "serial" -> "Номер"
    "subject" -> "Тема"
    else -> null
}


/**
 * Слова витка о том, что он добавил и уточнил (#1176, дословно владелец: «не забудь это
 * выводить в ui чтоб знание не терялось в графе»).
 *
 * Виток, чьё знание осело только в графе, для человека не случился. Сообщение перечисляет
 * прирост по-человечески: что нашлось, что уточнено, что подтвердилось согласием второго
 * исполнителя и где прочтения заспорили. Безымянное знание строкой не выходит. Пусто —
 * `null`: витку без прироста сказать нечего, и он говорит это своими словами.
 */
fun spiralDelta(before: Map<String, String>, after: Map<String, String>): String? {
    // Канонический структурный узел именуется своими якорями (#1176): ключ — слаг,
    // человеку показываются сырые «строка × колонка» из аннотаций.
    fun name(key: String): String? = understoodName(key)
        ?: key.takeIf { it.startsWith(META_CELL_ANCHOR_PREFIX) }?.let {
            val row = after[it + META_ANCHOR_ROW_SUFFIX] ?: before[it + META_ANCHOR_ROW_SUFFIX]
            val col = after[it + META_ANCHOR_COL_SUFFIX] ?: before[it + META_ANCHOR_COL_SUFFIX]
            if (row.isNullOrBlank() || col.isNullOrBlank()) null else "Ячейка «${row.take(24)}» × «${col.take(24)}»"
        }
    val fresh = mutableListOf<String>()
    val refined = mutableListOf<String>()
    val confirmed = mutableListOf<String>()
    val disputed = mutableListOf<String>()

    after.forEach { (key, value) ->
        if (isAnnotationKey(key) || isStateKey(key)) return@forEach
        val name = name(key) ?: return@forEach
        val was = before[key]
        when {
            was.isNullOrBlank() && value.isNotBlank() -> fresh += name
            !was.isNullOrBlank() && was != value -> refined += name
        }
    }
    after.keys.filter { it.endsWith(META_ALT_SUFFIX) }.forEach { altKey ->
        val key = altKey.removeSuffix(META_ALT_SUFFIX)
        val name = name(key) ?: return@forEach
        if (before[altKey].isNullOrBlank() && !after[altKey].isNullOrBlank()) disputed += name
    }
    after.keys.filter { it.endsWith(META_EVIDENCE_SUFFIX) }.forEach { evKey ->
        val key = evKey.removeSuffix(META_EVIDENCE_SUFFIX)
        val name = name(key) ?: return@forEach
        val grewAgreement = after[evKey].orEmpty().contains(AGREE_MARK) &&
            !before[evKey].orEmpty().contains(AGREE_MARK)
        if (grewAgreement && name !in fresh && name !in refined) confirmed += name
    }

    val parts = buildList {
        if (fresh.isNotEmpty()) add("нашлось: " + fresh.distinct().joinToString(", "))
        if (refined.isNotEmpty()) add("уточнено: " + refined.distinct().joinToString(", "))
        if (confirmed.isNotEmpty()) add("подтверждено: " + confirmed.distinct().joinToString(", "))
        if (disputed.isNotEmpty()) add("прочтения спорят: " + disputed.distinct().joinToString(", "))
    }
    if (parts.isEmpty()) return null
    return parts.joinToString(" · ").replaceFirstChar { it.uppercaseChar() }
}

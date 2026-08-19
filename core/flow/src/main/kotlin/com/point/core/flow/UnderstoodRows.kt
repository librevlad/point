package com.point.core.flow

/**
 * Как называется этот вид знания человеку.
 *
 * Рядом жила `understoodRows` — прежняя сборка строк знания, которую с появлением
 * `knowledgeRows` (спор, «ещё», подпись, открытые вопросы) не звал уже никто. Мёртвый
 * код удалён (#840): он выглядел как рабочий путь и приглашал править себя вместо
 * настоящего.
 */
fun understoodName(key: String): String? = when (key.removePrefix(META_ENTITY_PREFIX)) {
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
    val fresh = mutableListOf<String>()
    val refined = mutableListOf<String>()
    val confirmed = mutableListOf<String>()
    val disputed = mutableListOf<String>()

    after.forEach { (key, value) ->
        if (isAnnotationKey(key) || isStateKey(key)) return@forEach
        val name = understoodName(key) ?: return@forEach
        val was = before[key]
        when {
            was.isNullOrBlank() && value.isNotBlank() -> fresh += name
            !was.isNullOrBlank() && was != value -> refined += name
        }
    }
    after.keys.filter { it.endsWith(META_ALT_SUFFIX) }.forEach { altKey ->
        val key = altKey.removeSuffix(META_ALT_SUFFIX)
        val name = understoodName(key) ?: return@forEach
        if (before[altKey].isNullOrBlank() && !after[altKey].isNullOrBlank()) disputed += name
    }
    after.keys.filter { it.endsWith(META_EVIDENCE_SUFFIX) }.forEach { evKey ->
        val key = evKey.removeSuffix(META_EVIDENCE_SUFFIX)
        val name = understoodName(key) ?: return@forEach
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

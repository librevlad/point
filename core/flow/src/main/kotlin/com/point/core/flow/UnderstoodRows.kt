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
    "subject" -> "Тема"
    else -> null
}

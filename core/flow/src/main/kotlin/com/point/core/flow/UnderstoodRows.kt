package com.point.core.flow

data class UnderstoodRow(val name: String, val value: String)

fun understoodRows(metadata: Map<String, String>, limit: Int = 4): List<UnderstoodRow> {
    val useful = metadata.filterKeys { it.startsWith(META_ENTITY_PREFIX) && !isAnnotationKey(it) }
    return useful
        .filterKeys { it.removePrefix(META_ENTITY_PREFIX).none { c -> c == '.' } }
        .mapNotNull { (key, value) ->
            val name = understoodName(key) ?: return@mapNotNull null

            val extra = useful
                .filterKeys { it.startsWith("$key.") }
                .values.filter { it.isNotBlank() }
            UnderstoodRow(name, (listOf(value) + extra).joinToString(" "))
        }
        .take(limit)
}

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
    else -> null
}

package com.point.core.flow

/**
 * Что из понятого показать человеку и какими словами (#594).
 *
 * Три разных вещи лежат в метаданных объекта вперемешку, и до этого экран компьютера показывал их
 * одинаково — отсюда «Amount.src · ocr» рядом с «Сумма · 128500»:
 *
 * | что | пример | человеку |
 * |---|---|---|
 * | факт | `entity.amount` = `128500` | да, это ответ |
 * | уточнение факта | `entity.amount.currency` = `руб.` | да, но **вместе со своим числом** |
 * | след разбора | `entity.amount.src` = `ocr` | нет, это улика для нас |
 *
 * Правило, отделяющее след, живёт в ядре одним местом ([isAnnotationKey]) и здесь только
 * применяется. Прежде экран компьютера завёл своё («во втором имени есть точка — значит
 * служебное») и разошёлся с ядром: спрятал валюту, которую ядро прямо называет фактом. По
 * критерию владельца — понятое узнают, чтобы им воспользоваться — «128500» без «руб.» это не
 * сумма, а число.
 *
 * Живёт в `:core:flow`, потому что обе поверхности обязаны показывать понятое одинаково: два
 * правила о том, что такое факт, уже однажды разошлись.
 */
data class UnderstoodRow(val name: String, val value: String)

/**
 * Строки «ПОНЯЛ» для объекта.
 *
 * Ключ без человеческого имени не показывается вовсе: «Meter · 4102» — тот же жаргон, только
 * другой. Молчание честнее — и оно заметно нам, а не человеку: недостающее имя видно по пустой
 * строке в тесте, а не по странице английских ключей у него на экране.
 */
fun understoodRows(metadata: Map<String, String>, limit: Int = 4): List<UnderstoodRow> {
    val useful = metadata.filterKeys { it.startsWith(META_ENTITY_PREFIX) && !isAnnotationKey(it) }
    return useful
        .filterKeys { it.removePrefix(META_ENTITY_PREFIX).none { c -> c == '.' } }
        .mapNotNull { (key, value) ->
            val name = understoodName(key) ?: return@mapNotNull null
            // Уточнения приписываются к своему числу: «Сумма · 128 500 руб.», а не отдельной
            // строкой «Currency · руб.» под ним.
            val extra = useful
                .filterKeys { it.startsWith("$key.") }
                .values.filter { it.isNotBlank() }
            UnderstoodRow(name, (listOf(value) + extra).joinToString(" "))
        }
        .take(limit)
}

/**
 * Как назвать понятое по-человечески. `null` — имени нет, и показывать нечего.
 *
 * Список, а не преобразование ключа: «Meter» получается из ключа само собой и выглядит как слово,
 * оставаясь при этом английским именем поля.
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
    else -> null
}

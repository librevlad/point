package com.point.core.flow

/**
 * Похоже ли значение на адрес, по которому человек куда-то попадёт (#1028, #989).
 *
 * У телефона и адреса мерка правдоподобия есть, у ссылки не было вовсе: что назвали ссылкой,
 * то ссылкой и становилось. С фотографии машины точка в домене потерялась при распознавании —
 * `edrive.com.ua` прочиталось как `edrive com.ua`, — и знанием стал хвост `com.ua`, доменная
 * зона. Он занял строку рядом с верно прочитанным телефоном и потянул за собой действие
 * «открыть», которое никуда не ведёт.
 *
 * Правило бедное и одностороннее, как остальные в [semanticFits]: оно умеет сказать «это точно
 * не адрес» и молчит там, где жизнь богаче. Зону без имени сайта — `com.ua`, `co.uk`, `ua` —
 * ссылкой не считаем: имени, которое человек набрал бы в строке браузера, в ней нет.
 */
fun looksLikeLink(value: String): Boolean {
    val text = value.trim()
    if (text.isEmpty() || text.any(Char::isWhitespace)) return false

    val host = text
        .substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
        .substringBefore(':')
        .removePrefix("www.")
        .trim('.')
        .lowercase()

    val labels = host.split('.')
    if (labels.size < 2 || labels.any { it.isEmpty() }) return false

    val tld = labels.last()
    if (tld.length < 2 || !tld.all(Char::isLetter)) return false

    // Двухуровневая зона — `com.ua`, `co.uk`, `org.pl` — читается целиком, а не как имя сайта
    // в зоне `ua`. Иначе `com.ua` сходит за адрес, а `edrive.com.ua` — за сайт «com».
    val zone = if (labels[labels.size - 2] in ZONE_HEADS && tld.length == 2) 2 else 1
    return labels.size > zone
}

/**
 * Головы двухуровневых зон. Список короткий нарочно: это не реестр публичных суффиксов, а
 * ровно те зоны, что встречаются в документах человека.
 */
private val ZONE_HEADS = setOf("com", "net", "org", "gov", "edu", "co", "ac", "in", "biz", "info")

package com.point.core.flow

/**
 * Текст из XML-разметки OOXML — таким, каким его видит человек в Word или Excel (#1445).
 *
 * Строка в пакете бывает записана и именованными ссылками (`&amp;`, `&lt;`), и числовыми
 * (`&#1055;`, `&#x41F;`): так пишут библиотеки, и кириллица у них — сплошь числовые ссылки.
 * Читатель Word это раскодировал, читатель Excel — нет, и «Позиция» приходила человеку как
 * `&#1055;&#1086;&#1079;…`, а сущности в таком тексте не находились. Одно раскодирование на
 * оба читателя — чтобы два читателя одного формата не расходились.
 */
internal fun unescapeXml(s: String): String = NUMERIC_ENTITY.replace(s) { m ->
    val body = m.groupValues[1]
    val code = if (body.startsWith("x") || body.startsWith("X")) body.drop(1).toIntOrNull(16) else body.toIntOrNull()
    if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
}
    .replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&apos;", "'")
    .replace("&amp;", "&")

private val NUMERIC_ENTITY = Regex("&#(x?[0-9A-Fa-f]+);")

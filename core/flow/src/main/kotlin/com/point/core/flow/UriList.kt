package com.point.core.flow

/**
 * Адрес из содержимого `text/uri-list` (#999, решение владельца).
 *
 * Ссылка, переданная файлом, становилась объектом «Ссылка» без адреса: вид ставился по MIME
 * двери, сами байты никто не читал, и «Открыть ссылку» отвечало «Ссылка не найдена». Формат
 * простой (RFC 2483): по адресу на строку, строки с `#` — комментарии. Адресом считается
 * первая непустая строка не-комментарий, если она выглядит ссылкой. Адреса нет — это не
 * ссылка, а файл.
 */
fun uriListAddress(content: String): String? =
    content.lineSequence()
        .map(String::trim)
        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?.let { WEB_ADDRESS.find(it)?.value }

/** То же по первым байтам объекта — нулевой сигнал для классификатора. */
fun uriListAddress(head: ByteArray): String? =
    if (head.isEmpty()) null else uriListAddress(String(head, Charsets.UTF_8))

private val WEB_ADDRESS = Regex("""(?i)^https?://\S+""")

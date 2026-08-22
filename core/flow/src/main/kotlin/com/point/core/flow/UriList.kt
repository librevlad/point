package com.point.core.flow

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject

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

/**
 * То же по самому файлу объекта: читается только начало — любому адресу этого хватает.
 *
 * Голова, по которой классификатор ставит вид, короткая, и длинный адрес в неё не влезает —
 * значит спрашивать файл приходится ещё раз, но тем же правилом (#999).
 */
fun uriListAddressOf(path: String): String? = uriListAddress(fileHead(path, ADDRESS_CHARS))

/**
 * Объект, рождённый из файла, знает свой адрес (#999).
 *
 * Дверей, за которыми объект рождается из файла, несколько: приём, вещь из набора, результат
 * действия, приём с компьютера, возврат из «Недавнего». Правило одно — вид «Ссылка» поставлен
 * по байтам, значит из тех же байтов читается и адрес: он ложится знанием `entity.url` с
 * признаком `HAS_URL`, тем же, что у ссылки, присланной текстом. Знание уже приехало с
 * объектом — читать нечего; адреса в байтах нет — знание не выдумывается.
 *
 * Байты спрашиваются только у ссылки: у любого другого объекта файл не открывается вовсе.
 */
fun PointObject.knowingAddress(): PointObject {
    if (state.kind != ObjectKind.URL) return this
    val address = metadata[META_ENTITY_URL]?.takeIf(String::isNotBlank)
        ?: uriListAddressOf(uri.value)
        ?: return this
    return copy(
        state = state.with(Feature.HAS_URL),
        metadata = metadata + (META_ENTITY_URL to address),
    )
}

/** Знание адреса — тот же ключ, что у ссылки, найденной в тексте. */
const val META_ENTITY_URL = META_ENTITY_PREFIX + "url"

private val WEB_ADDRESS = Regex("""(?i)^https?://\S+""")

// Сколько символов читать ради адреса: любому адресу хватает, а весь файл читать незачем.
private const val ADDRESS_CHARS = 64 * 1024

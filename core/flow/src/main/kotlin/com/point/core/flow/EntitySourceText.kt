package com.point.core.flow

import com.point.core.model.Feature
import com.point.core.model.PointObject
import com.point.core.model.isFileBacked
import java.io.File

/**
 * Откуда действие берёт текст объекта: сидекар чтения, уже добытое знание, сам файл.
 *
 * Жил в `:executors` — переехал вместе с «Понять»/«Перевести»/«AI» (#1379): текст объекта
 * один и тот же на телефоне и компьютере, и добывается он одним правилом.
 */
fun entitySourceText(input: PointObject): String {
    val sidecar = input.metadata[META_OCR_TEXT_REF]
        ?.let { path -> runCatching { File(path).takeIf(File::isFile)?.readText() }.getOrNull() }
    if (sidecar != null) return sidecar

    // Уже добытое знание — источник наравне с сидекаром OCR: текст, который QR уже
    // отдал, «Понять»/«Перевести»/«AI» читают напрямую, а не молчат на пустых байтах
    // картинки. Та же логика, что уже вела «Открыть ссылку» на HAS_URL из QR (#693).
    // Ссылка в коде хранится ссылкой объекта, а не вторым фактом (#1119) — источник текста
    // тот же самый: что код отдал, то и читаем.
    input.metadata[META_ENTITY_PREFIX + "qr"]?.takeIf { it.isNotBlank() }?.let { return it }
    if (input.state.has(Feature.HAS_QR)) {
        input.metadata[META_ENTITY_PREFIX + "url"]?.takeIf { it.isNotBlank() }?.let { return it }
    }

    if (!input.state.kind.isFileBacked) return input.uri.value

    // Файл читается как текст только у текстового вида: сырые байты JPEG уходили
    // в облако «текстом страницы» вместе с EXIF (модель телефона, дата съёмки) —
    // и запирали визуальный путь понимания (охота 2026-08-09, HUNT2-F1).
    if (input.state.kind != com.point.core.model.ObjectKind.TEXT) return ""
    return File(input.uri.value).takeIf { it.isFile }?.readText().orEmpty()
}

suspend fun firstEntity(extractor: EntityExtractor, input: PointObject, type: EntityType): String? {

    // Уже добытое знание — первый источник: узел ссылки из QR — не файл, и «Открыть
    // ссылку» отвечало «Ссылка не найдена» рядом с «Нашёл ссылку» (скрин 2026-08-09).
    type.asMetaKey()?.let { key ->
        input.metadata[key]?.takeIf { it.isNotBlank() }?.let { return it }
    }

    if (type.asExtractedKind() == input.state.kind) return input.uri.value.takeIf { it.isNotBlank() }
    val text = entitySourceText(input)
    if (text.isBlank()) return null
    return extractor.extract(text).firstOrNull { it.type == type }?.value
}

private fun dialable(phone: String) = phone.filter { it.isDigit() || it == '+' }

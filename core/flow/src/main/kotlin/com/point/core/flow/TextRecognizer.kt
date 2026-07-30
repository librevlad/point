package com.point.core.flow

import com.point.core.model.PointObject

/**
 * On-device OCR — recognises text in an image with no network, key, or quota.
 * The realizer tries this first (free, offline, always available) and only falls
 * back to the cloud LLM for what on-device can't do (structured tables, hard
 * scans). Returns blank when nothing was recognised or the engine failed.
 */
interface TextRecognizer {
    suspend fun recognize(obj: PointObject): String
}

/**
 * Ридер, который отдаёт не только символы, но и их место на странице (#257).
 *
 * Отдельный контракт, а не обязанность каждого ридера: облачный текстовый ридер геометрии дать
 * не может, и заставлять его выдумывать координаты — значит получить адреса, ведущие в никуда.
 *
 * Плоский текст здесь **производный**, а не второе чтение: два независимых прохода по одному
 * снимку дают два разных ответа, и тогда значение, найденное по адресу, не совпадёт с текстом,
 * который человек видит на экране.
 */
interface AtomRecognizer : TextRecognizer {

    /** Всё прочитанное с местом на странице. Пустой слой, если прочитать не удалось. */
    suspend fun read(obj: PointObject): AtomLayer

    override suspend fun recognize(obj: PointObject): String = read(obj).text
}

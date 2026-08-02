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

    /**
     * Всё прочитанное с местом на странице.
     *
     * **Пустой слой означает «прочитал и ничего не нашёл» — и только это.** Движок на устройстве
     * так и отвечает: он всегда доходит до конца, просто иногда страница пуста.
     *
     * Ридер, который может **не дойти** (сеть отвалилась, ключ не принят, бесплатный лимит
     * кончился), пустым слоем прикрываться не имеет права: пустая страница и несостоявшееся
     * чтение выглядели бы для человека одинаково, а это две разные новости. Такой ридер **бросает**
     * — так делают облачные читатели (#280), и вызывающий обязан поймать и показать отказ. Раньше
     * здесь стояло «пустой слой, если прочитать не удалось», и с приходом сетевых ридеров эта
     * строка стала неправдой про половину реализаций.
     */
    suspend fun read(obj: PointObject): AtomLayer

    override suspend fun recognize(obj: PointObject): String = read(obj).text
}

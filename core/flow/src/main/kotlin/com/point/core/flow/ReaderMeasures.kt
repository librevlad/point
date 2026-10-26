package com.point.core.flow

/**
 * Что показал читатель страницы на корпусе владельца (#433, срез E).
 *
 * Порядок цепочки читателей раньше задавался порядком строк в коде — то есть мнением того, кто
 * их туда вписал. Теперь его задают эти числа: замер `tools/vision/freeprobe.py --truth` по
 * кадрам корпуса с эталоном (`tools/corpus/NN.expected.tsv`).
 *
 * [read] из [of] — сколько контрольных кусков эталона нашлось дословно в попытках, на которые
 * читатель ответил. Здесь меряется чтение, а не живость: отказ по лимиту и молчание уже ведёт
 * память исходов ([awakeFirst]), и она ставит такого читателя в конец поверх этого порядка.
 * Засчитанный нулём отказ записал бы лимит самого прогона читателю в плохое чтение.
 *
 * Запись стареет так же, как каталог сервисов: [measuredAt] сверяет недельная проверка
 * `tools/check_catalog_fresh.py`.
 */
data class ReaderMeasure(

    /** `CloudTextReader.reader` — имя читателя в цепочке. */
    val reader: String,

    val read: Int,

    val of: Int,

    /** Медиана времени ответа: при равной доле первым идёт тот, кто не заставляет ждать. */
    val medianMs: Long,

    val measuredAt: String,
) {

    val share: Double get() = if (of == 0) 0.0 else read.toDouble() / of
}

/**
 * Последний замер. Пополняется выводом `freeprobe.py --point`, а не руками.
 *
 * Кадры 01, 04, 06, 18, 23, по три попытки. Кадр 01 (миниатюра таблицы на снимке экрана) не
 * прочитал никто. OCR.space ответил на 5 попыток из 15 — дальше демо-ключ упёрся в свой предел,
 * 5 запросов за два часа. `mistral-ocr` замера нет: ключ отвечал 429 на все 25 попыток в двух
 * прогонах, и про его чтение прогон не узнал ничего. Он стоит после измеренных, пока не
 * ответит на прогоне.
 */
val READER_MEASURES: List<ReaderMeasure> = listOf(
    ReaderMeasure(reader = "ocr-space", read = 100, of = 120, medianMs = 8300, measuredAt = "26.10.2026"),
    ReaderMeasure(reader = "ovh-qwen-vl", read = 244, of = 360, medianMs = 41600, measuredAt = "26.10.2026"),
)

/**
 * Очередь читателей по замеру: лучшая доля прочитанного — первой, при равной — более быстрый.
 *
 * Читатель без замера идёт после измеренных, в прежнем порядке: «не мерили» — не «плох», но и
 * не повод ставить его выше того, за кого говорят числа.
 */
fun <T> inMeasuredOrder(
    readers: List<T>,
    measures: List<ReaderMeasure> = READER_MEASURES,
    nameOf: (T) -> String,
): List<T> {
    val byName = measures.associateBy { it.reader }
    val measured = readers.filter { nameOf(it) in byName }
        .sortedWith(
            compareByDescending<T> { byName.getValue(nameOf(it)).share }
                .thenBy { byName.getValue(nameOf(it)).medianMs },
        )
    return measured + readers.filter { nameOf(it) !in byName }
}

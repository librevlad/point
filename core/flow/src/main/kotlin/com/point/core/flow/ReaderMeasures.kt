package com.point.core.flow

/**
 * Что показал читатель страницы на корпусе владельца (#433, срез E).
 *
 * Порядок цепочки читателей раньше задавался порядком строк в коде — то есть мнением того, кто
 * их туда вписал. Теперь его задают эти числа: замер `tools/vision/freeprobe.py --truth` по
 * кадрам корпуса с эталоном (`tools/corpus/NN.expected.tsv`).
 *
 * [read] из [of] — сколько контрольных кусков эталона нашлось дословно во всех попытках вместе.
 * Попытка, на которую сервис не ответил, входит в [of] с нулём: молчащий читатель платит
 * временем человека, и число это помнит, а не прощает.
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

/** Последний замер. Пополняется выводом `freeprobe.py --point`, а не руками. */
val READER_MEASURES: List<ReaderMeasure> = listOf()

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

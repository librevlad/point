package com.point.core.flow

/**
 * Метрика корпуса: **доля документов, где действие выполнено без правок человеком** (#262).
 *
 * Не field-F1 и не «заполнено 6 из 9»: такое число подделывается количеством заполненных полей
 * и растёт, когда система заполняет пустоты. Здесь считается только то, ради чего человек
 * открыл документ: у кадра есть **ожидаемое действие**, и оно либо готово по своим критическим
 * полям ([ActionSchema.readiness]), либо нет. Бинарно, по кадру.
 *
 * Кадры, чьё ожидаемое действие ещё не имеет схемы (извлечь таблицу, передать показание
 * счётчика, построить маршрут), в знаменатель **не идут** — но и не исчезают: [CorpusScore.unscored]
 * называет их поимённо. Молчаливое сужение корпуса до удобных кадров — тот же грех, что
 * красивая ложь про «6 из 9».
 */
data class CorpusCase(
    /** Имя кадра корпуса — то же, что у файла: «11», «13». Значения кадра сюда не попадают. */
    val frame: String,
    /** Что человек пришёл сделать: [ActionSchema.id] либо имя действия без схемы. */
    val expectedAction: String,
    /** Факты объекта после прогона на устройстве — вход [actionReadiness]. */
    val facts: Map<String, String>,
)

/** Итог прогона корпуса: числитель, знаменатель и поимённо всё, что осталось за скобками. */
data class CorpusScore(
    val ready: List<String>,
    val notReady: List<String>,
    /** Кадры, чьё действие ещё не имеет схемы: считать их нечем, скрывать — нельзя. */
    val unscored: List<String>,
) {
    val scored: Int get() = ready.size + notReady.size
    /** Доля готовых среди измеримых; `null` — измерять пока нечего, и это честный ответ. */
    val share: Double? get() = if (scored == 0) null else ready.size.toDouble() / scored
}

/** Считает [CorpusScore] по кадрам: действие готово ⇔ его схема готова по фактам кадра. */
fun scoreCorpus(cases: List<CorpusCase>, schemas: List<ActionSchema> = ACTION_SCHEMAS): CorpusScore {
    val ready = mutableListOf<String>()
    val notReady = mutableListOf<String>()
    val unscored = mutableListOf<String>()
    cases.forEach { case ->
        val schema = schemas.firstOrNull { it.id == case.expectedAction }
        when {
            schema == null -> unscored += case.frame
            schema.readiness(case.facts) is Readiness.Ready -> ready += case.frame
            else -> notReady += case.frame
        }
    }
    return CorpusScore(ready, notReady, unscored)
}

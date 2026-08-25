package com.point.core.flow

/**
 * Режим, в котором человек заранее сказал «делай лучшее и не спрашивай» (#795).
 *
 * Конституция (§11) разрешает ровно это: «согласие может быть дано заранее — выбранным
 * режимом работы». Чего она не разрешает — подразумевать согласие по умолчанию, поэтому во
 * всякой сборке, которую ставят с сайта, режим выключен, пока его не включили руками. Сразу
 * он включён только в отладочной: там человек за экраном — сам разработчик (#1265).
 *
 * Режим не заводит нового пространства правил. Он меняет ответ в трёх местах, которые и
 * так спрашивают: согласие на чтение моделями, уровень «куда можно отправлять» и порядок
 * исполнителей.
 */
interface YoloMode {

    fun enabled(): Boolean

    suspend fun setEnabled(enabled: Boolean)

    companion object {

        /** Режима нет — всё спрашивается как обычно. Для тестов и для чистых сборок. */
        val OFF: YoloMode = object : YoloMode {
            override fun enabled() = false
            override suspend fun setEnabled(enabled: Boolean) = Unit
        }
    }
}

const val YOLO_TITLE = "Режим YOLO"

const val YOLO_WHAT =
    "Не спрашивать разрешения на облако и всегда брать самый сильный путь, даже если " +
        "он снаружи. Объект уходит на чужой сервер без вопроса."

/**
 * Дано ли согласие на облако (#795).
 *
 * Режим и есть согласие, данное заранее: конституция §11 разрешает именно такую форму.
 * Открытая ссылка режимом не открывается — `remembersConsent` про неё говорит «нет», и это
 * решает всё: выложить файл наружу спрашивают каждый раз, в любом режиме.
 */
fun cloudAllowedIn(scope: CloudScope, yolo: Boolean, remembered: Boolean): Boolean =
    remembersConsent(scope) && (yolo || remembered)

/**
 * Куда можно отправлять (#795). В режиме открыты все пути; выбранное человеком при этом не
 * стирается — выключит режим, и вернётся его уровень.
 */
fun privacyLevelIn(yolo: Boolean, chosen: PrivacyLevel): PrivacyLevel =
    if (yolo) PrivacyLevel.FREE_FIRST else chosen

/**
 * Порядок исполнителей, когда человек попросил лучшее.
 *
 * Обычный порядок бережёт: местное перед облачным, дешёвое перед дорогим. Здесь наоборот —
 * вперёд идёт тот, кто даёт лучший результат: облако, затем компьютер, затем сам телефон.
 * Внутри каждой ступени порядок прежний, по приоритету.
 *
 * Это только порядок, а не отказ от запасного пути: не вышло у первого — [FallbackRealizer]
 * спускается к следующему, ровно как и без режима.
 */
fun yoloOrder(candidates: List<Realizer>): List<Realizer> =
    candidates.sortedWith(
        compareBy(
            { strength(it.meta.kind) },
            { it.meta.priority },
            { it::class.java.name },
        ),
    )

private fun strength(kind: RealizerKind): Int = when (kind) {
    RealizerKind.CLOUD -> 0
    RealizerKind.REMOTE -> 1
    RealizerKind.LOCAL -> 2
}

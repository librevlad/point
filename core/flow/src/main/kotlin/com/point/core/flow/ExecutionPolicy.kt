package com.point.core.flow

import com.point.core.model.ObjectState

fun interface ExecutionPolicy {

    fun choose(state: ObjectState, candidates: List<Realizer>): List<Realizer>
}

/**
 * Насколько исполнитель близок человеку (#1088). Меньше — ближе.
 *
 * Сначала я сам, потом моё второе устройство, потом чужой сервис. Лестница объявлена один
 * раз и служит обоим режимам: бережный порядок сходит по ней сверху вниз ([carefulOrder]),
 * режим «делай лучшее» — снизу вверх ([yoloOrder]). Числа живут здесь, а не в порядке
 * констант перечисления, чтобы перестановка объявлений не меняла поведение продукта.
 */
fun nearness(kind: RealizerKind): Int = when (kind) {
    RealizerKind.LOCAL -> 0
    RealizerKind.REMOTE -> 1
    RealizerKind.CLOUD -> 2
}

/**
 * Бережный порядок исполнителей: дешёвое впереди дорогого, а при равной цене — сначала я,
 * потом мой компьютер, потом чужой сервис (#1088).
 *
 * Цену исполнитель объявляет сам приоритетом, и она решает первой: телефон слушает свою
 * запись до того, как отдать её кому бы то ни было (#1053). А вот при равной цене порядок
 * до сих пор задавало имя класса по алфавиту: «Распознать текст» одной и той же цены умеют
 * и чужой глаз (`ExternalEyeOcrRealizer`), и компьютер человека (`RemotePcRealizer`) — и
 * снимок уходил в чужой сервис только потому, что `E` раньше `R`. Теперь там правило: своё
 * делается своими руками, сосед по кругу идёт раньше чужого сервиса.
 *
 * Имя класса остаётся самым последним разделителем — для двух исполнителей, объявивших о
 * себе всё одинаковое, и только чтобы порядок был устойчив от запуска к запуску.
 */
fun carefulOrder(candidates: List<Realizer>): List<Realizer> =
    candidates.sortedWith(
        compareBy(
            { it.meta.priority },
            { nearness(it.meta.kind) },
            { it::class.java.name },
        ),
    )

class DefaultExecutionPolicy(

    /**
     * Человек мог заранее попросить лучшее вместо бережного (#795). Тогда порядок
     * исполнителей задаёт [yoloOrder], а годность кандидатов считается как всегда.
     */
    private val yolo: YoloMode = YoloMode.OFF,
) : ExecutionPolicy {
    override fun choose(state: ObjectState, candidates: List<Realizer>): List<Realizer> {
        val fit = candidates.filter { it.isAvailable() && it.accepts(state) }
        return if (runCatching { yolo.enabled() }.getOrDefault(false)) {
            yoloOrder(fit)
        } else {
            carefulOrder(fit)
        }
    }
}

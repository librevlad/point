package com.point.core.flow

/**
 * Просьбы компьютера, которые телефон разбирает у себя (#817).
 *
 * Связка была односторонней: телефон просил — компьютер делал. Обратно письмо доходило до
 * ящика телефона и гибло: перед каждой своей отправкой телефон вычищал ящик подчистую, считая
 * всё чужое мусором с прошлых разговоров.
 *
 * Решение владельца 13.08.2026: телефон правда выполняет просьбу, а чтобы человек не ждал
 * случайного открытия Point, приходит уведомление — «стук». Стук несёт одно слово «проверь
 * ящик»; сама просьба лежит зашифрованной здесь, на нашем сервере.
 *
 * Здесь — разбор письма, а не работа: что именно делать, знает `Realizer`, как везде.
 */
class PhoneRequests(
    private val seen: SeenLetters,

    /** Сделать работу над присланным объектом. Возвращает слова человеку или причину отказа. */
    private val run: suspend (Asked) -> Answered,
) {

    /** О чём просит компьютер: какое действие и над чем. */
    data class Asked(
        val action: String,
        val name: String,
        val mime: String,
        val bytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Asked && action == other.action && name == other.name)

        override fun hashCode(): Int = 31 * action.hashCode() + name.hashCode()
    }

    /** Что вышло. Компьютер узнаёт исход, а не тишину. */
    data class Answered(val done: String? = null, val failed: String? = null)

    /**
     * Разобрать одно письмо.
     *
     * `null` — это не наше дело: письмо либо ответ на наш же вопрос, либо чужого вида, и
     * трогать его нельзя. Повторно принесённая просьба узнаётся по номеру письма и работу
     * дважды не делает: сервер доставляет «хотя бы раз».
     */
    suspend fun answer(letterId: String, frame: PcFrame): Map<String, String>? {
        if (frame.meta[RelayRpc.KIND] != RelayRpc.RUN) return null
        val action = frame.meta[RelayRpc.RUN_ACTION]?.takeIf { it.isNotBlank() } ?: return null

        if (!seen.firstTime(letterId)) return replyTo(frame, Answered(done = ALREADY_DONE))

        val asked = Asked(
            action = action,
            name = frame.meta[RelayRpc.RUN_NAME].orEmpty().ifBlank { "объект" },
            mime = frame.meta[RelayRpc.RUN_MIME].orEmpty().ifBlank { "application/octet-stream" },
            bytes = frame.bytes,
        )
        val answered = runCatching { run(asked) }
            .getOrElse { Answered(failed = it.message?.takeIf { m -> m.isNotBlank() } ?: NOT_DONE) }
        return replyTo(frame, answered)
    }

    private fun replyTo(frame: PcFrame, answered: Answered): Map<String, String> = buildMap {
        put(RelayRpc.KIND, RelayRpc.REPLY)
        frame.meta[RelayRpc.ID]?.let { put(RelayRpc.ID, it) }
        answered.done?.let { put(RelayRpc.RUN_DONE, it) }
        answered.failed?.let { put(RelayRpc.RUN_FAILED, it) }
    }

    companion object {

        /** Письмо принесли второй раз: работа уже сделана, и делать её снова нельзя. */
        const val ALREADY_DONE = "Уже сделано"

        const val NOT_DONE = "Не вышло — телефон не смог это сделать"
    }
}

/**
 * Что человеку сказать про просьбу компьютера, пока он её не открыл.
 *
 * Уведомление называет работу и объект — по нему видно, на что человек соглашается тапом.
 */
fun phoneRequestNotice(action: String, name: String): String =
    "Компьютер просит: $action" + name.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()

package com.point.core.flow

/**
 * Что стук значит для телефона (#1398).
 *
 * Стук — одно слово «зайди»: что именно просит компьютер, телефон спрашивает у самого
 * компьютера. Путь от слова до человека имеет четыре выхода, и три из них — молчание.
 * Молчание бывает правильным: компьютер уснул, просьбу уже забрали, приехали одни исходы.
 * Но пока оно не называло себя, владелец видел ровно то, что видел: «не работает» — и
 * сказать, на каком шаге стук пропал, было нельзя.
 *
 * Решение вынесено сюда из службы: `FirebaseMessagingService` не поднять в обычном тесте,
 * а именно это правило и надо проверять. Служба остаётся тонкой дверью.
 */
sealed interface KnockMeaning {

    /** Позвать человека к работе, которая его ждёт. */
    data class Call(val action: String, val name: String) : KnockMeaning

    /** Ничего не делать — и сказать словами, почему. */
    data class Silent(val why: String) : KnockMeaning
}

/**
 * [word] — слово из письма, [linked] — привязан ли к телефону компьютер, [waiting] — очередь
 * компьютера или `null`, если прочитать её не вышло.
 */
fun knockMeaning(
    word: String?,
    linked: Boolean,
    waiting: List<PcOutboxEntry>?,
    somethingToDo: String = "сделать кое-что",
): KnockMeaning = when {
    word != KNOCK_ABOUT_OUTBOX -> KnockMeaning.Silent("письмо не про очередь: «${word ?: "пусто"}»")
    !linked -> KnockMeaning.Silent("к телефону не привязан компьютер")
    waiting == null -> KnockMeaning.Silent("очередь компьютера не прочиталась")
    else -> {
        val first = waiting.firstOrNull { !PcResultFields.outcomeOnly(it.meta) }
        when {
            first == null && waiting.isEmpty() -> KnockMeaning.Silent("очередь компьютера пуста")
            first == null -> KnockMeaning.Silent("в очереди только исходы без объекта: ${waiting.size}")
            else -> KnockMeaning.Call(
                action = first.meta[KNOCK_ACTION_LABEL].orEmpty().ifBlank { somethingToDo },
                name = first.meta["name"].orEmpty(),
            )
        }
    }
}

/** Слово, которым письмо зовёт заглянуть в очередь компьютера. */
const val KNOCK_ABOUT_OUTBOX = "outbox"

/** Название работы человеческими словами кладёт компьютер: он его и показывал. */
const val KNOCK_ACTION_LABEL = "pc.action.label"

/**
 * Куда телефон кладёт след стука (#1398).
 *
 * Не знание об объекте и не состояние: это память о том, что телефон услышал и что с этим
 * сделал. Без неё живой прогон не может назвать место, где стук пропал, — а гадать по шести
 * местам дороже, чем записать одну строку.
 */
fun interface KnockTrace {

    fun note(said: String)

    companion object {
        val Forgetful = KnockTrace { }
    }
}

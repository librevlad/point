package com.point.core.flow

import com.point.core.model.tr

/**
 * Что стук значит для телефона (#1398).
 *
 * Стук — одно слово «зайди»: что именно просит компьютер, телефон спрашивает у самого
 * компьютера. Путь от слова до человека имеет несколько выходов, и часть из них — молчание.
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

    /**
     * Сказать, что готова работа, о которой просил сам телефон (#1416).
     *
     * Это не просьба компьютера: «Компьютер просит: сделать кое-что» над готовым файлом
     * звало бы человека к работе, которой нет. [label] — как просьба называлась у человека.
     */
    data class Ready(val label: String, val name: String) : KnockMeaning

    /**
     * Стук пришёл, а очередь компьютера не прочиталась: сеть мигнула, сервер не ответил.
     *
     * Молчать здесь нельзя: компьютер стучит, только когда ему есть что сказать, и человек
     * узнал бы о просьбе лишь при следующем входе в Point. Android к тому же понижает
     * срочность писем, после которых приложение ничего не показало. Поэтому человека зовут
     * общими словами — что именно ждёт, телефон спросит по тапу.
     */
    data object Unread : KnockMeaning

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
    somethingToDo: String = tr("сделать кое-что"),
): KnockMeaning = when {
    word != KNOCK_ABOUT_OUTBOX -> KnockMeaning.Silent("письмо не про очередь: «${word ?: "пусто"}»")
    !linked -> KnockMeaning.Silent("к телефону не привязан компьютер")
    waiting == null -> KnockMeaning.Unread
    else -> {
        val first = waiting.firstOrNull { !PcResultFields.outcomeOnly(it.meta) }
        when {
            first == null && waiting.isEmpty() -> KnockMeaning.Silent("очередь компьютера пуста")
            first == null -> KnockMeaning.Silent("в очереди только исходы без объекта: ${waiting.size}")
            answersRequest(first.meta) -> KnockMeaning.Ready(
                label = first.meta[PcExecFields.LABEL].orEmpty(),
                name = first.meta["name"].orEmpty(),
            )
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

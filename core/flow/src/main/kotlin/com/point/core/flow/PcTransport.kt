package com.point.core.flow

import com.point.core.model.PointObject

/** Outcome of a phone→PC send — mapped to user-facing ActionResults by the realizer. */
sealed interface PcSendOutcome {
    /**
     * Байты доехали.
     *
     * [action] — что компьютер ответил про **заказанное действие**; `null` значит «неизвестно»:
     * действия не заказывали, компьютер старой сборки об исходе молчит или ещё не закончил.
     * Доставка и выполнение — разные события, и склеивать их нельзя: «файл доехал» ≠ «напечатано».
     */
    data class Sent(
        val action: PcActionOutcome? = null,
        /**
         * Что компьютер вернул, если действие родило объект.
         *
         * `null` — вернул только слово: так отвечают доставка («напечатал», «положил в папку») и
         * старая сборка компьютера, которая объектов возвращать не умела.
         */
        val returned: PcReturned? = null,
    ) : PcSendOutcome

    /**
     * Сервер не признал это устройство (401/403): его отключили из круга.
     *
     * Отдельно от [Unreachable], потому что чинится другим: не «подождите», а «войдите заново».
     */
    data object Rejected : PcSendOutcome

    /**
     * Письмо не легло в ящик компьютера.
     *
     * Три причины разведены, потому что человек чинит их по-разному, и шесть формулировок одного
     * «недоступен» он не различал вовсе (#524). [detail] остаётся для журнала и отладки — человеку
     * его показывают только там, где он что-то добавляет к [why].
     */
    data class Unreachable(val detail: String, val why: PcUnreachable = PcUnreachable.SERVER_SILENT) :
        PcSendOutcome
}

/**
 * Почему компьютер недоступен — три разных ответа вместо одного (#524).
 *
 * Прежде отказ говорил про брандмауэр Windows, порты и «сделайте сеть частной» — про мир, которого
 * уже нет: путь между устройствами один, и он идёт через сервер. Хуже того, все шесть прежних
 * формулировок описывали одно и то же событие разными словами, и человек не мог по ним понять, что
 * делать. Разных вещей ровно три, и каждая чинится своим движением.
 */
enum class PcUnreachable {
    /** Компьютера нет в круге: на нём не входили в этот аккаунт. Лечится входом на компьютере. */
    NOT_IN_CIRCLE,

    /** Компьютер в круге, но письма не забирает: «Point для ПК» не запущен. Лечится запуском. */
    PC_ASLEEP,

    /** До сервера Point не дозвониться. Лечится интернетом и ожиданием. */
    SERVER_SILENT,

    /** Объект больше, чем сервер берёт. Не чинится повтором — только объектом поменьше. */
    TOO_BIG,
}

/**
 * Сколько сервер Point берёт за одно письмо (`relay/point_server/mailbox.py`, `MAX_BLOB`).
 *
 * Проверяется ДО сети: иначе запись в поток умирает раньше, чем читается код ответа, и «слишком
 * большой» превращается в «недоступен» — ровно та подмена, из-за которой человек шёл чинить не то.
 */
const val PC_MAX_LETTER_BYTES = 50 * 1024 * 1024

/**
 * Отказ словами — по одной формулировке на причину (#524).
 *
 * Раньше их было шесть на три события, и человек не мог по ним понять, что делать: «Компьютер
 * недоступен», «Компьютер не подключён», «Компьютер отклонил», «Компьютер не узнал это
 * устройство» — все они описывали разное, а читались как одно. Теперь причин ровно столько,
 * сколько разных движений ими чинится, и каждая названа своим движением.
 *
 * Слова живут в `:core:flow`, потому что их обязаны говорить одинаково и пузырёк «На компьютер», и
 * действие, объявленное самим компьютером, и общий буфер: три соседних отказа об одном и том же
 * событии — это и есть те шесть формулировок, только собранные заново.
 */
fun pcUnreachableText(why: PcUnreachable): String = when (why) {
    PcUnreachable.NOT_IN_CIRCLE ->
        "Компьютера нет в вашем круге. Запустите «Point для ПК» и войдите в тот же аккаунт."
    PcUnreachable.PC_ASLEEP ->
        "Компьютер не отвечает. Проверьте, что «Point для ПК» на нём запущен."
    PcUnreachable.SERVER_SILENT ->
        "До сервера Point не дозвониться. Проверьте интернет и попробуйте ещё раз."
    PcUnreachable.TOO_BIG ->
        "Объект больше 50 МБ — столько за раз между устройствами не переслать."
}

/** Что сказать, когда сервер перестал признавать это устройство (401/403). */
const val PC_DEVICE_REVOKED = "Это устройство отключили от аккаунта. Войдите заново."

/**
 * Чем кончилось действие **на компьютере** (#114).
 *
 * Раньше телефон говорил «Напечатать на ПК — готово» в ответ на 200 от доставки файла: компьютер
 * исход выбрасывал (`runCatching { runAction(...) }`), а телефон переводил «доехало» в «готово».
 * Человек уходил в другую комнату к принтеру, которого нет.
 */
sealed interface PcActionOutcome {
    /** Сделано. [detail] — слова самого компьютера («В очереди «HP» · проверьте принтер»). */
    data class Done(val detail: String? = null) : PcActionOutcome

    /** Не вышло, и причина названа компьютером — телефон её не сочиняет и не сглаживает. */
    data class Failed(val reason: String) : PcActionOutcome
}

/**
 * Телефонная сторона разговора с компьютером (#147).
 *
 * Связывания здесь больше нет ни одной ручкой (#475): устройство попадает в круг входом в аккаунт,
 * а не рукопожатием, и «спариться» стало нечему. За швом — ящики сервера и ничего кроме них.
 */
interface PcTransport {

    suspend fun send(
        pc: LinkedPc,
        obj: PointObject,
        fileName: String,
        meta: Map<String, String>,
        action: String? = null,
    ): PcSendOutcome

    /** The PC's advertised remote actions (#80), or null when it is unreachable. */
    suspend fun fetchCaps(pc: LinkedPc): List<PcRemoteAction>?

    /** The PC's outbox listing (#161), or null when it is unreachable. */
    suspend fun fetchOutbox(pc: LinkedPc): List<PcOutboxEntry>?

    /** Stream one outbox entry into [targetPath]; false on any failure. */
    suspend fun downloadOutboxFile(pc: LinkedPc, id: Int, targetPath: String): Boolean

    /** Confirm the entry landed — the PC drops it from the outbox. */
    suspend fun ackOutbox(pc: LinkedPc, id: Int)

    /** Advertise the phone's own actions to the PC (#161 v2) — its cards grow
     *  «… · телефон» buttons. Quiet best-effort. */
    suspend fun pushPhoneCaps(pc: LinkedPc, caps: List<PcRemoteAction>): Boolean

    /**
     * Обменяться ключами сервисов со своим компьютером (#589).
     *
     * Не «отправить», а **обменяться**: в письме едут наши ключи, в ответе приезжают его. Один
     * раунд выравнивает оба устройства, и не нужно решать, кто главный.
     *
     * `null` — не дозвонились; тогда ключи остаются как были, и попробуем в следующий раз. Работа
     * молчаливая: человек её не заказывал, и рассказывать ему про неё нечего.
     */
    suspend fun exchangeSecrets(pc: LinkedPc, mine: SharedSecrets): SharedSecrets?
}

/** Cached remote actions of the linked PC — warm sync read for capability synthesis
 *  at process start (#80); refreshed when the circle arrives, dropped on sign-out. */
interface PcCapsStore {
    fun all(): List<PcRemoteAction>
    suspend fun save(caps: List<PcRemoteAction>)
    suspend fun clear()
}

/**
 * Компьютер, о котором телефон знает, — тёплое синхронное чтение, как у остальных крошечных
 * хранилищ.
 *
 * Правда о круге живёт на сервере, но первый экран обязан уложиться в 300 мс без сети: пузырёк «На
 * компьютер» решает, показываться ли, по этой записи. Она обновляется каждый раз, когда приезжает
 * круг, и стирается вместе с пропуском.
 */
interface PcLinks {
    fun current(): LinkedPc?
    suspend fun save(pc: LinkedPc)
    suspend fun clear()
}

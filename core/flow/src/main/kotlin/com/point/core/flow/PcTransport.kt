package com.point.core.flow

import com.point.core.model.PointObject

sealed interface PcSendOutcome {

    data class Sent(
        val action: PcActionOutcome? = null,

        val returned: PcReturned? = null,

        /** Знание об исходнике, добытое той стороной: перенос не теряет понятое (PC2). */
        val understanding: Map<String, String> = emptyMap(),
    ) : PcSendOutcome

    /**
     * Письмо легло на сервер, а компьютер за ним ещё не пришёл (#672).
     *
     * Это не отказ: объект доставлен и дождётся получателя. Прежнее «компьютер не
     * отвечает» описывало неответ RPC, а не судьбу письма, — человек верил и отправлял
     * заново, рискуя дублем на той стороне.
     */
    data object Parked : PcSendOutcome

    data object Rejected : PcSendOutcome

    data class Unreachable(val detail: String, val why: PcUnreachable = PcUnreachable.SERVER_SILENT) :
        PcSendOutcome
}

/** Судьба письма, а не статус RPC: доставлено и ждёт получателя (#672). */
const val PC_PARKED_TEXT = "Отправлено — компьютер заберёт, когда включится"

/**
 * Дошло ли письмо до получателя (#1269).
 *
 * Лежащее на сервере доставлено: компьютер придёт за ним и прочитает. Отказ и недоступность
 * — не дошло, и это не исключение, а обычное значение: спросить `runCatching { }.isSuccess`
 * значит услышать «дошло» при выключенном компьютере. `null` — отправка сорвалась.
 */
fun PcSendOutcome?.delivered(): Boolean =
    this is PcSendOutcome.Sent || this is PcSendOutcome.Parked

/**
 * Слово соседа о том, что работа ещё идёт (#1073).
 *
 * Объявлено здесь, а не только у компьютера: по нему телефон узнаёт, что ответ — ещё не
 * исход, и ждёт продолжения. Пока это знал один компьютер, телефону приходилось ждать по
 * признаку исполнителя — и он целую минуту стучался в очередь даже после «Скопировано в
 * буфер компьютера», то есть после законченной работы.
 */
const val PC_STILL_WORKING = "Компьютер ещё работает — готовое появится в списке «с компьютера»"

/**
 * Чем сменяется слово соседа, когда телефон перестаёт стеречь очередь (#1073).
 *
 * Слово соседа говорится за соседа: «ещё работает» — это то, что компьютер сказал минуту
 * назад, а не то, что телефон знает сейчас. Пока оно стояло дальше сторожа, у человека
 * оставалось обещание, которому нечем сбыться: диалог «Сохранить в…» человек держит
 * открытым дольше минуты обычно, а не изредка. Дальше телефон говорит от себя — и не
 * перестаёт ждать: исход доедет возвратом в Point, входом в объект или забором очереди.
 *
 * Живёт рядом с обещанием, которое сменяет: переписать одно, не увидев другого, нельзя.
 */
const val PC_NO_ANSWER_YET = "Компьютер пока не ответил — что вышло, будет видно здесь"

/**
 * Отмена человека у соседа — одно объявленное слово на оба устройства (#1073).
 *
 * Человек у компьютера закрыл системное окно «Сохранить в…»: сделано ничего, и сказать
 * телефону нечего — обещание гаснет тихо (решение владельца 21.08.2026: отмена человека не
 * ошибка). Остальное, что сосед сказал словами, телефон человеку повторяет.
 *
 * Слово объявлено, а не сверяется на глаз в двух местах, — тем же приёмом, что и
 * `failureIsWaiting` (#859): иначе переписать его у компьютера значило бы молча вернуть на
 * телефон вечное «ещё работает».
 */
const val PC_CANCELLED = "Отменено"

enum class PcUnreachable {

    NOT_IN_CIRCLE,

    PC_ASLEEP,

    SERVER_SILENT,

    TOO_BIG,

    /** Телефон сам знает, что сети нет (#690) — до сервера дело не дошло вообще. */
    NO_NETWORK,
}

const val PC_MAX_LETTER_BYTES = 50 * 1024 * 1024

/**
 * Сорвавшийся забор письма — не рассказ про компьютер (#1018).
 *
 * Письмо лежит на сервере, и его скачивание не зависит от того, включён ли компьютер:
 * объяснять срыв словами «компьютер не отвечает» значило посылать человека проверять не то.
 */
const val PC_PULL_FAILED_TEXT = "Не удалось забрать объект — проверьте интернет и попробуйте ещё раз"

fun pcUnreachableText(why: PcUnreachable): String = when (why) {
    PcUnreachable.NOT_IN_CIRCLE ->
        "Компьютера нет в вашем круге. Запустите «Point для ПК» и войдите в тот же аккаунт."
    PcUnreachable.PC_ASLEEP ->
        "Компьютер не отвечает. Проверьте, что «Point для ПК» на нём запущен."
    PcUnreachable.SERVER_SILENT ->
        "До сервера Point не дозвониться. Проверьте интернет и попробуйте ещё раз."
    PcUnreachable.TOO_BIG ->
        "Объект больше 50 МБ — столько за раз между устройствами не переслать."
    PcUnreachable.NO_NETWORK -> NO_NETWORK_TEXT
}

const val PC_DEVICE_REVOKED = "Это устройство отключили от аккаунта. Войдите заново."

sealed interface PcActionOutcome {

    data class Done(val detail: String? = null) : PcActionOutcome

    data class Failed(val reason: String) : PcActionOutcome
}

interface PcTransport {

    suspend fun send(
        pc: LinkedPc,
        obj: PointObject,
        fileName: String,
        meta: Map<String, String>,
        action: String? = null,
    ): PcSendOutcome

    suspend fun fetchCaps(pc: LinkedPc): List<PcRemoteAction>?

    suspend fun fetchOutbox(pc: LinkedPc): List<PcOutboxEntry>?

    suspend fun downloadOutboxFile(pc: LinkedPc, id: Int, targetPath: String): Boolean

    suspend fun ackOutbox(pc: LinkedPc, id: Int)

    suspend fun pushPhoneCaps(pc: LinkedPc, caps: List<PcRemoteAction>): Boolean

    suspend fun exchangeSecrets(pc: LinkedPc, mine: SharedSecrets): SharedSecrets?
}

interface PcCapsStore {
    fun all(): List<PcRemoteAction>
    suspend fun save(caps: List<PcRemoteAction>)
    suspend fun clear()

    /** Когда устройство объявлялось в последний раз; `null` — не объявлялось вовсе. */
    fun savedAt(): Long? = null
}

/**
 * Сколько живёт объявление чужого устройства (#633, #624).
 *
 * Правило одно на обе стороны связки: телефон судит о компьютере и компьютер о телефоне
 * по одному сроку. Порознь у них разъехались бы и сроки, и поведение — молча.
 */
const val CAPS_FRESH_MS: Long = 6 * 60 * 60 * 1000

/**
 * Можно ли выдавать чужое состояние за нынешнее.
 *
 * Устарело — Point молчит: причина недоступности не показывается вовсе. Молчание честнее
 * выдуманного текста, а «компьютер не вошёл в аккаунт» недельной давности — именно выдумка.
 */
fun capsFresh(savedAt: Long?, now: Long, ttl: Long = CAPS_FRESH_MS): Boolean =
    savedAt != null && savedAt > 0 && now - savedAt < ttl

interface PcLinks {
    fun current(): LinkedPc?
    suspend fun save(pc: LinkedPc)
    suspend fun clear()
}

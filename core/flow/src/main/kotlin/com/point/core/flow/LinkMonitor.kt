package com.point.core.flow

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Кто когда кого слышал (#412).
 *
 * Транспорт — единственное место, которое знает правду о связи: он только что сходил к компьютеру
 * (или не смог) и знает, каким путём. Экран об этом узнать сам не может, поэтому транспорт
 * рассказывает, а монитор помнит.
 *
 * Помнит ровно один факт — последний контакт и его путь. Истории здесь нет намеренно: человеку
 * нужен ответ «сейчас есть связь или нет», а не журнал.
 */
interface LinkMonitor {
    val last: StateFlow<Contact?>

    /** Устройство на том конце ответило — таким путём. */
    fun heard(path: LinkPath)

    /**
     * Забыть контакт (#451): устройства разошлись, и помнить о связи с ними больше нечего.
     *
     * Нужен ровно потому, что память теперь переживает перезапуск: контакт со ВЧЕРАШНИМ
     * компьютером, дожив до связи со следующим, рассказывал бы о нём чужую правду.
     */
    fun forget()

    data class Contact(val at: Long, val path: LinkPath)
}

/**
 * Где последний контакт переживает перезапуск приложения (#451).
 *
 * Шов, а не хранилище: `:core:flow` обязан оставаться Android-free, поэтому «куда записать» решает
 * `:data`. Тому, кто помнит, всё равно — prefs это, файл или ничего.
 */
interface LinkLog {
    fun read(): LinkMonitor.Contact?
    fun write(contact: LinkMonitor.Contact)
    fun clear()
}

/** Ничего не переживает — для тестов и для стороны, которой память между запусками не нужна. */
class ForgetfulLinkLog : LinkLog {
    private var contact: LinkMonitor.Contact? = null
    override fun read(): LinkMonitor.Contact? = contact
    override fun write(contact: LinkMonitor.Contact) { this.contact = contact }
    override fun clear() { contact = null }
}

/**
 * Помнит последний контакт — в том числе после перезапуска (#451).
 *
 * До этого память жила только в процессе, и компьютер, с которым работали вчера, встречал человека
 * фразой «ещё не связывались» — утверждением о прошлом, которого не было. Забытое — это не
 * «ни разу»; разница между ними и есть весь баг.
 *
 * Чтение отложено до первого обращения: монитор собирается вместе с графом, а первый экран обязан
 * укладываться в 300 мс без I/O. Экран связи — единственный, кому нужен [last], и открывается он
 * по тапу, а не на старте.
 */
class RememberingLinkMonitor(
    private val log: LinkLog = ForgetfulLinkLog(),
    private val clock: () -> Long = System::currentTimeMillis,
) : LinkMonitor {
    private val remembered by lazy { MutableStateFlow(log.read()) }
    override val last: StateFlow<LinkMonitor.Contact?> get() = remembered.asStateFlow()

    override fun heard(path: LinkPath) {
        val contact = LinkMonitor.Contact(clock(), path)
        remembered.value = contact
        log.write(contact)
    }

    override fun forget() {
        remembered.value = null
        log.clear()
    }
}

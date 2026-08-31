package com.point.core.flow

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface LinkMonitor {
    val last: StateFlow<Contact?>

    fun heard()

    fun forget()

    data class Contact(val at: Long)
}

interface LinkLog {
    fun read(): LinkMonitor.Contact?
    fun write(contact: LinkMonitor.Contact)
    fun clear()
}

class ForgetfulLinkLog : LinkLog {
    private var contact: LinkMonitor.Contact? = null
    override fun read(): LinkMonitor.Contact? = contact
    override fun write(contact: LinkMonitor.Contact) { this.contact = contact }
    override fun clear() { contact = null }
}

class RememberingLinkMonitor(
    private val log: LinkLog = ForgetfulLinkLog(),
    private val clock: () -> Long = System::currentTimeMillis,
) : LinkMonitor {
    private val remembered by lazy { MutableStateFlow(log.read()) }
    override val last: StateFlow<LinkMonitor.Contact?> get() = remembered.asStateFlow()

    override fun heard() {
        val contact = LinkMonitor.Contact(clock())
        remembered.value = contact
        log.write(contact)
    }

    override fun forget() {
        remembered.value = null
        log.clear()
    }
}

/**
 * Сколько компьютер считается живым после того, как отозвался (#545).
 *
 * Срок человеческий, а не сетевой: компьютер, ответивший минуту назад, для человека рядом,
 * и предлагать ему продолжение на нём — правда. Через полчаса это уже догадка, и действие
 * возвращается на своё обычное место, а не обещает то, чего никто не проверял.
 */
const val PC_AWAKE_WITHIN_MS = 10L * 60 * 1000

/**
 * Отзывался ли компьютер недавно — то есть рядом ли он сейчас (#545).
 *
 * Живость записывает тот, кто её видел: ответ компьютера на просьбу и свежая отметка круга.
 * Молчание сервера или выключенный компьютер сюда не попадают вовсе — «не слышали» остаётся
 * «не слышали», а не превращается в «выключен».
 */
fun awake(monitor: LinkMonitor, now: Long = System.currentTimeMillis()): Boolean =
    monitor.last.value?.let { now - it.at in 0..PC_AWAKE_WITHIN_MS } ?: false

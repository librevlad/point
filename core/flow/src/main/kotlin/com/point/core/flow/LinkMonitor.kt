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

    data class Contact(val at: Long, val path: LinkPath)
}

class InMemoryLinkMonitor(private val clock: () -> Long = System::currentTimeMillis) : LinkMonitor {
    private val _last = MutableStateFlow<LinkMonitor.Contact?>(null)
    override val last: StateFlow<LinkMonitor.Contact?> = _last.asStateFlow()

    override fun heard(path: LinkPath) {
        _last.value = LinkMonitor.Contact(clock(), path)
    }
}

package com.point.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Геометрия компакт-окна и peek-плашки: правый нижний угол рабочей области
 * (решение владельца 2026-08-09: «компакт-окно из трея в правой части экрана»).
 * Чистые функции — рабочую область (без таскбара) даёт вызывающий.
 */
data class ScreenArea(val x: Int, val y: Int, val width: Int, val height: Int)

data class WindowBounds(val x: Int, val y: Int, val width: Int, val height: Int)

const val COMPACT_WIDTH = 380
const val COMPACT_HEIGHT = 620
const val PEEK_WIDTH = 340
const val PEEK_HEIGHT = 88
const val SCREEN_MARGIN = 12

fun compactBounds(work: ScreenArea): WindowBounds {
    val height = minOf(COMPACT_HEIGHT, work.height - SCREEN_MARGIN * 2)
    return WindowBounds(
        x = work.x + work.width - COMPACT_WIDTH - SCREEN_MARGIN,
        y = work.y + work.height - height - SCREEN_MARGIN,
        width = COMPACT_WIDTH,
        height = height,
    )
}

/** Плашка встаёт туда же, где появится компакт: клик не прыгает по экрану. */
fun peekBounds(work: ScreenArea): WindowBounds = WindowBounds(
    x = work.x + work.width - PEEK_WIDTH - SCREEN_MARGIN,
    y = work.y + work.height - PEEK_HEIGHT - SCREEN_MARGIN,
    width = PEEK_WIDTH,
    height = PEEK_HEIGHT,
)

const val PEEK_LIFETIME_MS = 8_000L

/**
 * Peek — собственная плашка Point, не системное уведомление: прибыло с телефона →
 * высветилась, клик — вылезло окошко на этом объекте, сама гаснет по сроку.
 */
class PeekState(private val now: () -> Long) {

    private data class Shown(val item: InboxItem, val at: Long)

    private val shown = MutableStateFlow<Shown?>(null)

    /** Тик для UI: смена значения — повод перечитать current(). */
    val pulse: StateFlow<Any?> get() = shown.asStateFlow()

    fun arrived(item: InboxItem, compactVisible: Boolean) {
        if (compactVisible) return
        shown.value = Shown(item, now())
    }

    fun current(): InboxItem? {
        val s = shown.value ?: return null
        if (now() - s.at >= PEEK_LIFETIME_MS) {
            shown.value = null
            return null
        }
        return s.item
    }

    fun take(): InboxItem? {
        val opened = current()
        shown.value = null
        return opened
    }

    fun dismiss() {
        shown.value = null
    }
}

package com.point.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Геометрия компакт-окна и peek-плашки: правый нижний угол рабочей области
 * (решение владельца 2026-08-09: «компакт-окно из трея в правой части экрана»).
 * Чистые функции — рабочую область (без панели задач) даёт вызывающий.
 */
data class ScreenArea(val x: Int, val y: Int, val width: Int, val height: Int)

data class WindowBounds(val x: Int, val y: Int, val width: Int, val height: Int)

const val COMPACT_WIDTH = 380
const val COMPACT_HEIGHT = 620
const val PEEK_WIDTH = 340
const val PEEK_HEIGHT = 100
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
 * Куда ведёт Escape (#1025): на один уровень назад, как «←» текущего экрана.
 * Раздел настроек → корень настроек → список; объект → список; список → спрятать окно.
 * Прежде Esc из настроек прятал окно целиком — человек терял место, где стоял.
 */
enum class EscapeStep { SETTINGS_SECTION_BACK, SETTINGS_CLOSE, OBJECT_CLOSE, WINDOW_HIDE }

fun escapeStep(settingsOpen: Boolean, settingsAtRoot: Boolean, objectOpen: Boolean): EscapeStep = when {
    settingsOpen && !settingsAtRoot -> EscapeStep.SETTINGS_SECTION_BACK
    settingsOpen -> EscapeStep.SETTINGS_CLOSE
    objectOpen -> EscapeStep.OBJECT_CLOSE
    else -> EscapeStep.WINDOW_HIDE
}

/** Реакция на новое прибытие: из списка — открыть сразу, из чужой сцены — пригласить. */
enum class ArrivalReaction { OPEN, INVITE }

fun arrivalReaction(openedId: String?): ArrivalReaction =
    if (openedId == null) ArrivalReaction.OPEN else ArrivalReaction.INVITE

/**
 * Явный зов человека «покажись» (#1019, решение владельца 20.08.2026, вариант B):
 * «Открыть в Point», пробуждение второй копией, запуск с файлом. Каждый зов — ровно
 * один подъём окна. Счётчик, а не Boolean: уже видимое, но погребённое под чужими
 * окнами окно тоже обязано выйти вперёд, а Boolean второй раз не срабатывает.
 */
class RaiseSignal {

    private val _calls = MutableStateFlow(0)

    /** Сколько раз позвали: каждое новое значение — повод поднять окно один раз. */
    val calls: StateFlow<Int> = _calls.asStateFlow()

    fun call() {
        _calls.update { it + 1 }
    }
}

/**
 * Peek — собственная плашка Point, не системное уведомление: прибыло с телефона →
 * высветилась, клик — вылезло окошко на этом объекте, сама гаснет по сроку.
 */
class PeekState(private val now: () -> Long) {

    private data class Shown(val item: InboxItem, val at: Long, val source: ObjectSource)

    private val shown = MutableStateFlow<Shown?>(null)

    /** Тик для UI: смена значения — повод перечитать current(). */
    val pulse: StateFlow<Any?> get() = shown.asStateFlow()

    fun arrived(item: InboxItem, compactVisible: Boolean, source: ObjectSource = ObjectSource.PHONE_RELAY) {
        if (compactVisible) return

        // Брошенное в окно и взятое из буфера не пикает: человек сам это сделал и видит.
        if (source == ObjectSource.DROPPED || source == ObjectSource.CLIPBOARD) return
        shown.value = Shown(item, now(), source)
    }

    fun sourceOfCurrent(): ObjectSource? = shown.value?.takeIf { now() - it.at < PEEK_LIFETIME_MS }?.source

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

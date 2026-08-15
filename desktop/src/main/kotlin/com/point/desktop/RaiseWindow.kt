package com.point.desktop

/**
 * Окно, которое умеет выйти вперёд. Системное окно — сторонний эффект и живёт за
 * интерфейсом: порядок шагов подъёма проверяется без экрана.
 */
interface RaisableWindow {

    /** «Поверх всех» — временная мера подъёма, а не образ жизни окна. */
    var alwaysOnTop: Boolean

    var iconified: Boolean

    fun toFront()

    fun requestFocus()
}

/**
 * Поднять окно на зов человека (#1019). «Открыть в Point» из проводника принимал объект
 * молча: `visible = true` снимает скрытие, но не двигает окно в порядке окон, а уже
 * видимое окно от него не меняется вовсе — три объекта подряд ушли в Point вслепую.
 *
 * Windows не отдаёт передний план процессу, который на нём не стоит, поэтому окно сначала
 * объявляется верхним, поднимается и тут же перестаёт быть верхним: решение «окно не висит
 * поверх чужой работы» (12.08.2026) остаётся в силе — поверх всех оно ровно на один шаг.
 */
fun raiseAboveOthers(window: RaisableWindow) {
    if (window.iconified) window.iconified = false
    window.alwaysOnTop = true
    window.toFront()
    window.requestFocus()
    window.alwaysOnTop = false
}

/** Тот же подъём, но для настоящего окна AWT. */
class AwtWindow(private val frame: java.awt.Frame) : RaisableWindow {

    override var alwaysOnTop: Boolean
        get() = runCatching { frame.isAlwaysOnTop }.getOrDefault(false)
        set(value) {
            runCatching { frame.isAlwaysOnTop = value }
        }

    override var iconified: Boolean
        get() = frame.extendedState and java.awt.Frame.ICONIFIED != 0
        set(value) {
            val state = frame.extendedState
            frame.extendedState =
                if (value) state or java.awt.Frame.ICONIFIED else state and java.awt.Frame.ICONIFIED.inv()
        }

    override fun toFront() {
        frame.toFront()
    }

    override fun requestFocus() {
        frame.requestFocus()
    }
}

package com.point.desktop

/**
 * Звук прибытия объекта на компьютер (#650).
 *
 * Файл — тот же, что играет телефон при уходе (`data/src/main/res/raw`, подключён в
 * ресурсы модуля): только общий файл даёт общий тембр, ради которого пара и затевалась.
 * Тишина здесь никогда не ошибка: нет звуковой карты, занят выход, выключено в настройках —
 * объект всё равно пришёл, и говорить об этом человеку нечего.
 */
fun interface PortalSound {
    fun arrived()
}

class JvmPortalSound(
    private val enabled: () -> Boolean,
) : PortalSound {

    override fun arrived() {
        if (!runCatching { enabled() }.getOrDefault(true)) return
        runCatching {
            val bytes = javaClass.getResourceAsStream("/$ARRIVAL")?.readBytes() ?: return
            Thread({ runCatching { play(bytes) } }, "point-portal-sound").apply { isDaemon = true }.start()
        }
    }

    private fun play(bytes: ByteArray) {
        javax.sound.sampled.AudioSystem
            .getAudioInputStream(java.io.ByteArrayInputStream(bytes))
            .use { stream ->
                val line = javax.sound.sampled.AudioSystem.getClip()
                line.open(stream)
                line.start()

                // Клип держит линию открытой до конца проигрывания: закрыть сразу — оборвать
                // звук на первой миллисекунде.
                line.addLineListener { event ->
                    if (event.type == javax.sound.sampled.LineEvent.Type.STOP) runCatching { line.close() }
                }
            }
    }

    private companion object {
        const val ARRIVAL = "point_portal_in.wav"
    }
}

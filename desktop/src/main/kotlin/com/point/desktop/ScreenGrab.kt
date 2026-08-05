package com.point.desktop

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO

/**
 * Снимок экрана как объект Point (#585) — четвёртый вход на компьютере.
 *
 * Три прежних входа — перетащить файл, взять из буфера, прислать с телефона — все требуют, чтобы
 * объект уже где-то существовал. Но самое частое на компьютере не лежит файлом нигде: это то, что
 * сейчас на экране. Окно с ошибкой, таблица в чужой программе, переписка, счёт в браузере.
 *
 * Телефон так не умеет и не сможет: там снимок экрана делает система, и до Point он доходит
 * только через «Поделиться», то есть уже как готовый файл. На компьютере это одно движение.
 *
 * Берётся **весь виртуальный экран**, а не главный монитор: на двух мониторах человек снимает то,
 * что видит, и объяснять ему, какой из них «главный», Point не станет.
 */
class ScreenGrab(private val dir: File) {

    /**
     * Снять экран и вернуть файл; `null` — система не дала (например, среда без экрана вовсе).
     *
     * Отказ здесь молчаливый намеренно: зовущий знает, что делать (сказать человеку словами), а
     * причина у него всегда одна и та же — снять не вышло.
     */
    fun take(): File? = runCatching {
        if (GraphicsEnvironment.isHeadless()) return null
        val bounds = virtualBounds() ?: return null
        val image = Robot().createScreenCapture(bounds)
        dir.mkdirs()
        val file = File(dir, "screen-" + System.currentTimeMillis() + ".png")
        ImageIO.write(image, "png", file)
        file.takeIf { it.length() > 0 }
    }.getOrNull()

    /** Прямоугольник, накрывающий все мониторы: снимаем то, что человек видит целиком. */
    private fun virtualBounds(): Rectangle? {
        val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        if (screens.isEmpty()) return null
        var union: Rectangle? = null
        screens.forEach { device ->
            device.configurations.forEach { config ->
                union = union?.union(config.bounds) ?: config.bounds
            }
        }
        return union?.takeIf { it.width > 0 && it.height > 0 }
    }
}

package com.point.desktop

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO

class ScreenGrab(private val dir: File) {

    fun take(): File? = runCatching {
        if (GraphicsEnvironment.isHeadless()) return null
        val bounds = virtualBounds() ?: return null
        val image = Robot().createScreenCapture(bounds)
        dir.mkdirs()
        val file = File(dir, "screen-" + System.currentTimeMillis() + ".png")
        ImageIO.write(image, "png", file)
        file.takeIf { it.length() > 0 }
    }.getOrNull()

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

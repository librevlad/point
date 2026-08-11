package com.point.desktop.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ПК и телефон — один продукт, а не два похожих.
 *
 * Значок и цвет действия у Android-UI и Compose Desktop лежат в двух файлах: общего модуля
 * у них нет, а `:core:ui` — Android. Значит расходиться они будут молча, и однажды одно и то
 * же «Понять» окажется фиолетовой звёздочкой на телефоне и серым кружком на ПК.
 *
 * Сторож сверяет не цвета попиксельно, а состав: какие ключи действий обе стороны знают.
 * Появился ключ на телефоне — он обязан появиться и здесь.
 */
class DesktopKeepsPhoneLookTest {

    private val phone = File("../core/ui/src/main/kotlin/com/point/core/ui/BubbleIcons.kt")

    private val here = File("src/main/kotlin/com/point/desktop/ui/BubbleIcons.kt")

    private fun keysOf(file: File, function: String): Set<String> {
        val body = file.readText().substringAfter("fun $function(").substringBefore("\n}")
        return Regex("""^\s*"([^"]+)"\s*(?:,\s*"[^"]+"\s*)*->""", RegexOption.MULTILINE)
            .findAll(body)
            .flatMap { line -> Regex("\"([^\"]+)\"").findAll(line.value).map { it.groupValues[1] } }
            .toSet()
    }

    @Test
    fun `набор значков действий на ПК тот же, что на телефоне`() {
        assertTrue("телефонный файл не найден: сторож слеп", phone.isFile)

        assertEquals(keysOf(phone, "bubbleIcon"), keysOf(here, "bubbleIcon"))
    }

    @Test
    fun `набор цветов действий на ПК тот же, что на телефоне`() {
        assertTrue("телефонный файл не найден: сторож слеп", phone.isFile)

        assertEquals(keysOf(phone, "bubbleColor"), keysOf(here, "bubbleColor"))
    }
}

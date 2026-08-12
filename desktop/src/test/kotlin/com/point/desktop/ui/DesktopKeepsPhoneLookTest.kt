package com.point.desktop.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ПК и телефон — один продукт, а не два похожих (#849).
 *
 * Раньше значок и цвет действия лежали в двух файлах, и сторож сверял наборы ключей. Он
 * ловил пропавший ключ, но не ловил разъехавшийся цвет: одно и то же «Понять» могло стать
 * фиолетовой звездой на телефоне и серым кружком на ПК при зелёных тестах.
 *
 * Теперь файл один, а компилируется дважды — каждый раз своим Compose. Сверять стало
 * нечего, и сторожить надо не совпадение, а сам шов: копия не вернулась, общий каталог
 * подключён обеими сторонами. Шов, который никто не держит, разбирают по частям.
 */
class DesktopKeepsPhoneLookTest {

    private val shared = File("../core/ui/src/shared/kotlin/com/point/core/ui/BubbleIcons.kt")

    @Test
    fun `значки и цвета действий объявлены один раз — в общем каталоге`() {
        assertTrue("общий исходник значков пропал: $shared", shared.isFile)

        val text = shared.readText()
        assertTrue("в общем файле нет значков действий", text.contains("fun bubbleIcon("))
        assertTrue("в общем файле нет цветов действий", text.contains("fun bubbleColor("))
    }

    @Test
    fun `копия значков на ПК не завелась заново`() {
        val copy = File("src/main/kotlin/com/point/desktop/ui/BubbleIcons.kt")

        assertFalse("копия вернулась — значки снова разъедутся молча", copy.isFile)
    }

    @Test
    fun `общий каталог подключён обеими сторонами`() {
        val here = File("build.gradle.kts").readText()
        val phone = File("../core/ui/build.gradle.kts").readText()

        assertTrue("ПК не компилирует общий каталог", here.contains("core/ui/src/shared/kotlin"))
        assertTrue("телефон не компилирует общий каталог", phone.contains("src/shared/kotlin"))
    }
}

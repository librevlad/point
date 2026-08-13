package com.point.desktop.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ПК и телефон — один продукт, а не два похожих (#849).
 *
 * Раньше значок и цвет действия лежали в двух файлах, и тест сверял наборы ключей. Он
 * ловил пропавший ключ, но не ловил разъехавшийся цвет: одно и то же «Понять» могло стать
 * фиолетовой звездой на телефоне и серым кружком на ПК при зелёных тестах.
 *
 * Теперь файл один, а компилируется дважды — каждый раз своим Compose. Сверять стало
 * нечего, и проверять надо не совпадение, а само место склейки: копия не вернулась, общий каталог
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

    /**
     * Цвет палитры, набранный литералом второй раз, — это и есть будущее расхождение (#851).
     * Тест смотрит только на два файла тем: там литерал означает «палитру завели заново».
     */
    @Test
    fun `палитра не набрана литералами второй раз`() {
        val palette = File("../core/ui/src/shared/kotlin/com/point/core/ui/PointPalette.kt")
        assertTrue("общая палитра пропала: $palette", palette.isFile)

        // Белый — не токен, а отсутствие оттенка: он же стоит контрастом на акценте
        // (`onPrimary`), и проверять его значило бы ловить шум вместо расхождения.
        val declared = Regex("""Color\((0x[0-9A-F]{8})\)""").findAll(palette.readText())
            .map { it.groupValues[1] }
            .filterNot { it == "0xFFFFFFFF" }
            .toSet()

        val desktop = File("src/main/kotlin/com/point/desktop/ui/PointDesktopTheme.kt")
        val phone = File("../core/ui/src/main/kotlin/com/point/core/ui/theme/PointTheme.kt")

        // У телефона есть ещё светлая схема — там белый и есть белый, а не токен палитры.
        val themes = listOf(
            desktop.name to desktop.readText(),
            phone.name to phone.readText().substringAfter("darkColorScheme(").substringBefore("\n)"),
        )

        val repeated = themes.flatMap { (name, text) ->
            declared.filter { text.contains("Color($it)") }.map { "$name: $it" }
        }

        assertTrue("цвет палитры объявлен заново: $repeated", repeated.isEmpty())
    }

    /**
     * Схема — то, чем красятся стандартные компоненты. Собранная второй раз, она молча
     * назначает те же цвета другим ролям: до #853 `secondary` на телефоне был приглушённым
     * серым, а на ПК — cyan.
     */
    @Test
    fun `тёмная схема собрана один раз — в общем каталоге`() {
        val shared = File("../core/ui/src/shared/kotlin/com/point/core/ui/PointColorSchemes.kt")
        assertTrue("общая схема пропала: $shared", shared.isFile)

        val themes = listOf(
            File("src/main/kotlin/com/point/desktop/ui/PointDesktopTheme.kt"),
            File("../core/ui/src/main/kotlin/com/point/core/ui/theme/PointTheme.kt"),
        )

        val rebuilt = themes.filter { it.readText().contains("darkColorScheme(") }.map { it.name }

        assertTrue("схема собирается заново: $rebuilt", rebuilt.isEmpty())
    }
}

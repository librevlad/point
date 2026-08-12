package com.point.desktop.ui

import com.point.core.flow.AI_PROVIDERS
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Строка сервиса в настройках не зависит от длины примечания (#876).
 *
 * Живой прогон владельца: описание сервиса вытянулось в колонку по одной букве. Причина —
 * в `Row` рядом со взвешенной колонкой стоял текст без всякого ограничения ширины: он брал
 * себе столько, сколько просил, а колонке доставался остаток. На узком окне остатка не
 * оставалось вовсе.
 *
 * Проверить раскладку Compose Desktop без окна нечем, поэтому сторож держит две вещи, из
 * которых беда и складывалась: длинные примечания в списке сервисов и правило «примечание
 * живёт внутри взвешенной колонки, а не отдельным столбцом».
 */
class SettingsRowKeepsWidthTest {

    private val source = File("src/main/kotlin/com/point/desktop/ui/SettingsScreen.kt").readText()

    @Test
    fun `примечания у сервисов действительно длинные — короткими они не станут`() {
        val longest = AI_PROVIDERS.mapNotNull { it.freeNote }.maxByOrNull { it.length }.orEmpty()

        assertTrue("примечание пропало: сторож ослеп", longest.isNotBlank())
        assertTrue("самое длинное примечание — «$longest»", longest.length > 30)
    }

    @Test
    fun `примечание живёт внутри взвешенной колонки, а не отдельным столбцом`() {
        val row = source.substringAfter("private fun Service(").substringBefore("\n@Composable")
        val column = row.substringAfter("Modifier.weight(1f)").substringBefore("\n        }")

        assertTrue("примечание вынесено из колонки — ширина снова уедет", "freeNote" in column)
    }

    /**
     * Жёсткая ширина шире окна и создавала нехватку места: окно компакта уже 560.
     */
    @Test
    fun `колонка настроек не шире окна`() {
        assertTrue("ширина задана жёстко", "widthIn(max = 560.dp)" in source)
        assertTrue("колонка не растягивается по окну", "fillMaxWidth().widthIn" in source)
    }
}

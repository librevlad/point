package com.point.source

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Из отказа «навсегда» есть дорога дальше (#455).
 *
 * Раньше на этом месте был тост «Без этого доступа не получится» и закрытие экрана. Тому, кто
 * однажды выбрал «больше не спрашивать», система отказывает мгновенно и окна не показывает — то
 * есть он получал один и тот же тост при каждом тапе и не мог узнать, что решение переехало в
 * системные настройки. Тост тестом не ловится по природе, поэтому дорога живёт на экране.
 */
@RunWith(RobolectricTestRunner::class)
class SourcePickerScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `закрытый навсегда доступ назван словами и ведёт в настройки`() {
        var settings = 0
        compose.setContent {
            PointTheme {
                SourcePickerScreen(
                    sources = emptyList(),
                    onPick = {},
                    blocked = "Место",
                    onOpenSettings = { settings++ },
                )
            }
        }

        // Имя источника — в словах: человек должен понять, о чём именно речь.
        compose.onNodeWithText("Место", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Открыть настройки Point").performClick()

        assertEquals("дорога в настройки не сработала — человек снова в тупике", 1, settings)
    }

    @Test fun `из закрытого доступа есть выход, а не одна дорога`() {
        var dismissed = false
        compose.setContent {
            PointTheme {
                SourcePickerScreen(
                    sources = emptyList(),
                    onPick = {},
                    blocked = "Место",
                    onDismissBlocked = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("Не сейчас").performClick()
        assertTrue("экран закрытого доступа не отпускает человека", dismissed)
    }

    @Test fun `обычный выбор источника ничего про настройки не говорит`() {
        var picked: String? = null
        compose.setContent {
            PointTheme {
                SourcePickerScreen(sources = listOf(fakeSource("Камера")), onPick = { picked = it.label })
            }
        }

        compose.onNodeWithText("Что превратить в объект?").assertIsDisplayed()
        compose.onNodeWithText("Открыть настройки Point").assertDoesNotExist()
        compose.onNodeWithText("Камера").performClick()

        assertEquals("Камера", picked)
    }
}

/** Источник для экрана: ему нужны только имя и иконка — всё остальное здесь не вызывается. */
private fun fakeSource(name: String) = object : ObjectSource {
    override val id = name
    override val label = name
    override val icon = "camera"
    override fun isAvailable(context: Context) = true
    override suspend fun request(context: Context): Intent? = null
    override suspend fun read(context: Context, data: Intent?): Produced? = null
}

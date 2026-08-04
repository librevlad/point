package com.point.source

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Экран выбора источника: все пятеро на виду и плитку шторки предлагают строкой (#456), а из
 * отказа «навсегда» есть дорога дальше (#455).
 *
 * Про #455: раньше на этом месте был тост «Без этого доступа не получится» и закрытие экрана.
 * Тому, кто однажды выбрал «больше не спрашивать», система отказывает мгновенно и окна не
 * показывает — то есть он получал один и тот же тост при каждом тапе и не мог узнать, что решение
 * переехало в системные настройки. Тост тестом не ловится по природе, поэтому дорога живёт на
 * экране.
 */
@RunWith(RobolectricTestRunner::class)
class SourcePickerScreenTest {

    @get:Rule val compose = createComposeRule()

    private val all = listOf(
        fakeSource("Буфер обмена", "copy"),
        fakeSource("Камера", "camera"),
        fakeSource("Голос", "transcribe"),
        fakeSource("Место", "map"),
        fakeSource("Принять файл", "link"),
    )

    @Test fun `все пять источников названы на экране`() {
        compose.setContent { PointTheme { SourcePickerScreen(sources = all, onPick = {}) } }

        all.forEach { compose.onNodeWithText(it.label).assertExists() }
    }

    @Test fun `плитку предлагают строкой, и тап по ней просит систему`() {
        var asked = false
        compose.setContent {
            PointTheme {
                SourcePickerScreen(sources = all, onPick = {}, tileOffer = true, onAddTile = { asked = true })
            }
        }

        compose.onNodeWithText(TILE_ROW).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("тап по предложению плитки никуда не ведёт", asked)
    }

    @Test fun `когда плитка уже стоит, предложения на экране нет`() {
        compose.setContent { PointTheme { SourcePickerScreen(sources = all, onPick = {}, tileOffer = false) } }

        compose.onNodeWithText(TILE_ROW).assertDoesNotExist()
    }

    @Test fun `в тупике закрытого доступа плитку не предлагают`() {
        // Дорога там ровно одна — настройки; звать человека в шторку было бы шумом поверх тупика.
        compose.setContent {
            PointTheme {
                SourcePickerScreen(sources = emptyList(), onPick = {}, blocked = "Место", tileOffer = true)
            }
        }

        compose.onNodeWithText(TILE_ROW).assertDoesNotExist()
    }

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

private const val TILE_ROW = "Поставить плитку в шторку"

/** Источник для экрана: ему нужны только имя и иконка — всё остальное здесь не вызывается. */
private fun fakeSource(name: String, iconKey: String = "camera") = object : ObjectSource {
    override val id = name
    override val label = name
    override val icon = iconKey
    override fun isAvailable(context: Context) = true
    override suspend fun request(context: Context): Intent? = null
    override suspend fun read(context: Context, data: Intent?): Produced? = null
}

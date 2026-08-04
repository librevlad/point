package com.point.source

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Экран выбора источника (#456): все пятеро на виду, и плитку шторки предлагают строкой.
 */
@RunWith(RobolectricTestRunner::class)
class SourcePickerScreenTest {

    @get:Rule val compose = createComposeRule()

    private val all = listOf(
        fakeSource("clipboard", "Буфер обмена", "copy"),
        fakeSource("camera", "Камера", "camera"),
        fakeSource("voice", "Голос", "transcribe"),
        fakeSource("location", "Место", "map"),
        fakeSource("receive", "Принять файл", "link"),
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
}

private const val TILE_ROW = "Поставить плитку в шторку"

/** Экрану от источника нужны только имя и иконка — остальное он не спрашивает. */
private fun fakeSource(sourceId: String, sourceLabel: String, iconKey: String) = object : ObjectSource {
    override val id = sourceId
    override val label = sourceLabel
    override val icon = iconKey
    override fun isAvailable(context: Context) = true
    override suspend fun request(context: Context): Intent? = null
    override suspend fun read(context: Context, data: Intent?): Produced? = null
}

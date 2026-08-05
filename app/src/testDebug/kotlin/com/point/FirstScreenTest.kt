package com.point

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.point.core.model.ActionYield
import com.point.core.model.Bubble
import com.point.core.model.BubbleTier
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.ui.FirstScreen
import com.point.core.ui.Outcome
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Первый экран — тот, ради которого человек и открыл Point (#239).
 *
 * Он рисуется каждый раз, когда объект доехал, и до сих пор не композился ни в одном тесте:
 * `@Preview` показывает его владельцу, но CI на превью не смотрит. Значит выломать отрисовку
 * действий — и остаться зелёным — можно было в любой момент; человек при этом получал объект,
 * с которым нечего сделать.
 *
 * Проверяются ровно четыре обещания экрана: действия нарисованы, тап зовёт именно то действие,
 * запрос ввода занимает их место, отказ виден словами.
 */
@RunWith(RobolectricTestRunner::class)
class FirstScreenTest {

    @get:Rule val compose = createComposeRule()

    private val obj = PointObject(
        id = "o",
        mime = "application/pdf",
        uri = ScratchRef("/scratch/o.pdf"),
        state = ObjectState(ObjectKind.PDF),
        metadata = mapOf("name" to "Накладная.pdf"),
    )

    private val understand = Bubble(
        icon = "understand",
        title = "Понять",
        capabilityId = CapabilityId("understand"),
        expectedNextState = ObjectState(ObjectKind.TEXT),
        tier = BubbleTier.SMART,
        intent = Intent.UNDERSTAND,
        yields = ActionYield.Same,
    )

    private val share = Bubble(
        icon = "share",
        title = "Поделиться",
        capabilityId = CapabilityId("share"),
        expectedNextState = ObjectState(ObjectKind.PDF),
        tier = BubbleTier.INSTANT,
        intent = Intent.SEND,
        yields = ActionYield.None,
    )

    private fun screen(
        bubbles: List<Bubble> = listOf(understand, share),
        message: String? = null,
        inputPrompt: String? = null,
        onBubble: (Bubble) -> Unit = {},
    ) {
        compose.setContent {
            PointTheme(darkTheme = true) {
                FirstScreen(
                    obj = obj,
                    bubbles = bubbles,
                    onBubble = onBubble,
                    message = message,
                    messageOutcome = if (message == null) Outcome.NONE else Outcome.FAILED,
                    inputPrompt = inputPrompt,
                )
            }
        }
    }

    /** Объект без действий — тупик: человек пришёл спросить «что я могу с этим сделать». */
    @Test fun `действия объекта нарисованы и разложены по разделам`() {
        screen()

        compose.onNodeWithText("Понять").assertIsDisplayed()
        compose.onNodeWithText("ИЗВЛЕЧЬ").assertExists() // раздел подписан разрядкой заглавными
        compose.onNodeWithText("Поделиться").assertExists()
        compose.onNodeWithText("ОТПРАВИТЬ").assertExists()
    }

    /** Тап зовёт ИМЕННО то действие, по которому попали, — а не первое попавшееся. */
    @Test fun `тап по действию зовёт именно его`() {
        var tapped: Bubble? = null
        screen(onBubble = { tapped = it })

        compose.onNodeWithText("Понять").performClick()

        assertEquals(understand, tapped)
    }

    /** Действие попросило слов — экран спрашивает, а список действий уступает ему место. */
    @Test fun `запрос ввода занимает место действий`() {
        screen(inputPrompt = "Что сделать с этим документом?")

        compose.onNodeWithText("Что сделать с этим документом?").assertIsDisplayed()
        compose.onNodeWithText("Готово").assertExists()
        compose.onNodeWithText("Понять").assertDoesNotExist()
    }

    /** Исход виден на самом объекте — иначе отказ неотличим от «ничего не произошло». */
    @Test fun `отказ виден словами на экране объекта`() {
        screen(message = "Не удалось открыть объект")

        compose.onNodeWithText("Не удалось открыть объект").assertIsDisplayed()
    }
}

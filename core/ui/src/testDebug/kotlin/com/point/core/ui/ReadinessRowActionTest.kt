package com.point.core.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_ENTITY_TRACK
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadinessRowActionTest {

    @get:Rule val compose = createComposeRule()

    private val saveContact = Bubble(
        icon = "contact",
        title = "Сохранить контакт",
        capabilityId = CapabilityId("save-contact"),
        expectedNextState = ObjectState(ObjectKind.TEXT),
    )

    private val parcelWithPhone = mapOf(
        META_ENTITY_TRACK to "20 4514 9154 9395",
        META_GRAPH_ROLE_PREFIX + "carrier" to "Нова Пошта",
        META_ENTITY_PREFIX + "phone" to "+380504327707",
    )

    private fun section(
        metadata: Map<String, String>,
        bubbles: List<Bubble> = listOf(saveContact),
        enabled: Boolean = true,
        onBubble: (Bubble) -> Unit = {},
    ) = compose.setContent {
        PointTheme {
            ReadinessSection(
                metadata = metadata,
                bubbles = bubbles,
                enabled = enabled,
                onBubble = onBubble,
            )
        }
    }

    @Test fun `тап по готовой строке запускает её действие`() {
        var tapped: Bubble? = null
        section(parcelWithPhone) { tapped = it }

        compose.onNodeWithText("Сохранить контакт", substring = true).performClick()

        assertEquals(CapabilityId("save-contact"), tapped?.capabilityId)
    }

    @Test fun `готовая строка без реализации остаётся справкой — тап ничего не запускает`() {

        var tapped: Bubble? = null
        section(parcelWithPhone) { tapped = it }

        compose.onNodeWithText("Номер отправления", substring = true).performClick()

        assertNull(tapped)
    }

    @Test fun `тап по неготовой строке по-прежнему раскрывает, чего не хватает`() {
        var tapped: Bubble? = null
        section(mapOf(META_GRAPH_ROLE_PREFIX + "carrier" to "Нова Пошта")) { tapped = it }

        compose.onNodeWithText("Номер отправления", substring = true).performClick()

        compose.onNodeWithText("офлайн не нашлось", substring = true).assertExists()
        assertNull("неготовое ничего не запускает", tapped)
    }

    @Test fun `пока идёт действие, карточка тапов не принимает`() {
        var tapped: Bubble? = null
        section(parcelWithPhone, enabled = false) { tapped = it }

        compose.onNodeWithText("Сохранить контакт", substring = true).performClick()

        assertNull(tapped)
    }

    @Test fun `действие объекту не предложено — строка кнопкой не притворяется`() {

        var tapped: Bubble? = null
        section(parcelWithPhone, bubbles = emptyList()) { tapped = it }

        compose.onNodeWithText("Сохранить контакт", substring = true).performClick()

        assertNull(tapped)
    }
}

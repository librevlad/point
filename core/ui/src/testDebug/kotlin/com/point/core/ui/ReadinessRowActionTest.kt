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

/**
 * Карточка готовности под пальцем (#464).
 *
 * Находка владельца была именно про палец: «эти вещи некликабельны, по крайней мере были». Проверять
 * это чистой функцией недостаточно — тап живёт на экране, и до сих пор ни один тест по этой карточке
 * не стучал. Robolectric поднимает настоящий Android в JVM: эмулятор не нужен, ворота те же
 * (`./gradlew test`).
 */
@RunWith(RobolectricTestRunner::class)
class ReadinessRowActionTest {

    @get:Rule val compose = createComposeRule()

    private val saveContact = Bubble(
        icon = "contact",
        title = "Сохранить контакт",
        capabilityId = CapabilityId("save-contact"),
        expectedNextState = ObjectState(ObjectKind.TEXT),
    )

    /** Скрин с посылкой и телефоном: одна строка запускается, вторая — сегодня ещё справка. */
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
        // «Отследить отправление» готово, но открывать страницу перевозчика Point не умеет.
        // Молча подсунуть сюда чужое действие было бы тем же обманом, только наоборот.
        var tapped: Bubble? = null
        section(parcelWithPhone) { tapped = it }

        compose.onNodeWithText("Отследить отправление", substring = true).performClick()

        assertNull(tapped)
    }

    @Test fun `тап по неготовой строке по-прежнему раскрывает, чего не хватает`() {
        var tapped: Bubble? = null
        section(mapOf(META_GRAPH_ROLE_PREFIX + "carrier" to "Нова Пошта")) { tapped = it }

        compose.onNodeWithText("Отследить отправление", substring = true).performClick()

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
        // Тот же гейт, что в `runner`, но глазами пальца: пузыря нет — тап не запускает ничего.
        var tapped: Bubble? = null
        section(parcelWithPhone, bubbles = emptyList()) { tapped = it }

        compose.onNodeWithText("Сохранить контакт", substring = true).performClick()

        assertNull(tapped)
    }
}

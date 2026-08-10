package com.point.core.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.ui.theme.PointTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #693 (охота 2026-08-10): найденное значение было видно и недоступно — ни
 * скопировать, ни продолжить с ним работу. Решение владельца «Копия и вход в
 * содержимое»: любое показанное значение берётся одним касанием.
 */
@RunWith(RobolectricTestRunner::class)
class FactRowCopyTest {

    @get:Rule val compose = createComposeRule()

    private val qrObject = PointObject(
        id = "qr",
        mime = "image/png",
        uri = ScratchRef("/scratch/qr.png"),
        state = ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_QR)),
        metadata = mapOf(META_ENTITY_PREFIX + "qr" to "Ночь: связь после реанимации"),
    )

    @Test fun `тап по найденному значению копирует его и говорит об этом`() {
        compose.setContent {
            PointTheme {
                UnderstoodSection(facts = understoodFacts(qrObject), enriching = emptyList())
            }
        }

        compose.onNodeWithText("Ночь: связь после реанимации", substring = true).performClick()

        compose.onNodeWithText("Скопировано").assertExists()
    }

    @Test fun `значения без факта не притворяются кнопкой`() {
        val vcard = qrObject.copy(
            state = ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_VCARD)),
            metadata = emptyMap(),
        )
        compose.setContent {
            PointTheme {
                UnderstoodSection(facts = understoodFacts(vcard), enriching = emptyList())
            }
        }

        // «Это визитка» не несёт value — тап по такой строке ничего не копирует
        // и не падает.
        compose.onNodeWithText("Это визитка", substring = true).performClick()
        compose.onNodeWithText("Скопировано").assertDoesNotExist()
    }
}

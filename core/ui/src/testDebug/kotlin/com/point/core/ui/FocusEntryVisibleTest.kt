package com.point.core.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Вход в обводку виден, а не угадывается (#641): целая функция Focus открывалась только тапом
 * по картинке, и на экране не было ни одного намёка на это.
 */
@RunWith(RobolectricTestRunner::class)
class FocusEntryVisibleTest {

    @get:Rule val compose = createComposeRule()

    private fun obj(kind: ObjectKind) = PointObject(
        id = "id",
        mime = if (kind == ObjectKind.IMAGE) "image/jpeg" else "text/plain",
        uri = ScratchRef("/scratch/объект"),
        state = ObjectState(kind),
    )

    private fun screen(kind: ObjectKind, onTap: () -> Unit = {}) {
        compose.setContent {
            PointTheme {
                FirstScreen(obj = obj(kind), bubbles = emptyList(), onBubble = {}, onHeroTap = onTap)
            }
        }
    }

    @Test fun `у снимка значок обводки виден`() {
        screen(ObjectKind.IMAGE)

        compose.onNodeWithContentDescription(FOCUS_ENTRY_LABEL).assertHasClickAction()
    }

    /**
     * Решение владельца 11.08.2026 (#798): «вынести вперёд и подсветить». Значок рисуется
     * поверх превью — на живом прогоне тёмный кружок под кругом терялся на снимке, и три
     * способности за ним прятались.
     */
    @Test fun `значок обводки нарисован поверх превью, а не под ним`() {
        screen(ObjectKind.IMAGE)

        val mark = compose.onNodeWithContentDescription(FOCUS_ENTRY_LABEL).fetchSemanticsNode()
        // Знак объекта называется человеческим словом, а не именем вида в коде (#825):
        // голосовой доступ читает «Изображение», а не «IMAGE».
        val hero = compose.onNodeWithContentDescription(kindMarkLabel(KindMark.IMAGE)).fetchSemanticsNode()

        assertTrue("значок объявлен после превью", mark.id > hero.id)
    }

    @Test fun `у текста значка обводки нет — обводить нечего`() {
        screen(ObjectKind.TEXT)

        compose.onNodeWithContentDescription(FOCUS_ENTRY_LABEL).assertDoesNotExist()
    }

    @Test fun `тап по значку открывает то же, что и тап по превью`() {
        var opened = 0
        screen(ObjectKind.IMAGE) { opened++ }

        compose.onNodeWithContentDescription(FOCUS_ENTRY_LABEL).performClick()

        assertEquals(1, opened)
    }
}

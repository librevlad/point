package com.point

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.point.core.model.Bubble
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExitRowTest {

    @get:Rule val compose = createComposeRule()

    private fun objectScreen(leaveLabel: String? = null, onLeave: () -> Unit = {}) {
        val obj = PointObject(
            id = "o",
            mime = "text/plain",
            uri = ScratchRef("/scratch/o.txt"),
            state = ObjectState(ObjectKind.TEXT),
            metadata = mapOf("name" to "Пришлите договор до пятницы"),
        )
        val state = FlowUiState(frame = FlowFrame(obj, emptyList<Bubble>()))
        compose.setContent {
            PointTheme(darkTheme = true) {
                if (leaveLabel == null) {
                    PointHost(state = state, onBubble = {}, onSubmitInput = {}, onCancelInput = {}, onDismissMessage = onLeave)
                } else {
                    PointHost(
                        state = state,
                        onBubble = {},
                        onSubmitInput = {},
                        onCancelInput = {},
                        onDismissMessage = onLeave,
                        leaveLabel = leaveLabel,
                    )
                }
            }
        }
    }

    @Test fun `выход зовёт выход, как бы он ни назывался`() {
        var left = false
        objectScreen(leaveLabel = LEAVE_BACK, onLeave = { left = true })

        compose.onNodeWithText(LEAVE_BACK).performClick()

        assertTrue("выход нарисован, но никуда не ведёт", left)
    }

    @Test fun `дверь «Поделиться» не обещает «Недавнее»`() {
        objectScreen(leaveLabel = LEAVE_BACK)

        compose.onNodeWithText("← Назад").assertExists()
        compose.onNodeWithText("← Недавнее").assertDoesNotExist()
    }

    @Test fun `без имени двери выход ведёт домой и так и назван`() {
        objectScreen()

        compose.onNodeWithText("← Недавнее").assertExists()
    }
}

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class ShareDoorExitTest {

    @get:Rule val compose = createEmptyComposeRule()

    @Test fun `расшаренный текст открывается с честным выходом`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ShareActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Пришлите договор до пятницы")

        ActivityScenario.launch<ShareActivity>(intent).use {
            compose.waitUntilAtLeastOneExists(hasText("← Назад"), TIMEOUT_MS)
            compose.onNodeWithText("← Недавнее").assertDoesNotExist()
        }
    }

    @Test fun `расшаренный текст назван своими словами, а не именем временного файла`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ShareActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Пришлите договор до пятницы")

        ActivityScenario.launch<ShareActivity>(intent).use {
            compose.waitUntilAtLeastOneExists(hasText("Пришлите договор до пятницы"), TIMEOUT_MS)
            compose.onAllNodesWithText("shared-", substring = true).assertCountEquals(0)
        }
    }
}

private const val TIMEOUT_MS = 10_000L

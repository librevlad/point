package com.point.desktop.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.point.core.flow.capabilities.sharedCapabilities
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.desktop.DesktopRegistry
import com.point.desktop.DesktopResolver
import com.point.desktop.DesktopState
import com.point.desktop.InboxItem
import com.point.desktop.ObjectSource
import com.point.desktop.desktopCapabilities
import org.junit.Rule
import org.junit.Test

/**
 * Список действий на компьютере ведёт себя как на телефоне (ревью экранов 02.09.2026).
 *
 * Компьютер показывал ВСЕ действия группы сразу: у снимка выходила стена в два десятка
 * одинаковых строк — «помочь выбрать» превращалось в «вывалить всё, что умею». Телефон тем
 * же общим правилом (`likelyCount`, #879) показывает вероятное, а остальное — по просьбе.
 */
class ObjectActionsHierarchyTest {

    @get:Rule val compose = createComposeRule()

    private val state = DesktopState(
        registry = DesktopRegistry(desktopCapabilities { true } + sharedCapabilities()),
        resolver = DesktopResolver(emptySet()),
        clipboard = { },
    )

    private fun shot(): InboxItem {
        val obj = PointObject(
            "shot",
            "image/png",
            ScratchRef("/tmp/снимок.png"),
            ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_TEXT)),
        )
        val item = InboxItem(obj)
        state.onReceived(item, ObjectSource.LOCAL)
        return state.items.value.first { it.obj.id == obj.id }
    }

    private fun show(item: InboxItem) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PointDesktopTheme {
                CompactObject(state = state, item = item, onBack = { })
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test
    fun `группа показывает вероятное, остальное открывается по просьбе`() {
        show(shot())

        // «Показать ещё N» есть у свёрнутых групп — значит список не вывален целиком.
        compose.onAllNodesWithText("Показать ещё", substring = true)
            .fetchSemanticsNodes().isNotEmpty()
            .let { org.junit.Assert.assertTrue("группы не свёрнуты — экран вываливает всё", it) }
    }

    @Test
    fun `по просьбе открывается остальное, и его можно свернуть обратно`() {
        show(shot())

        compose.onAllNodesWithText("Показать ещё", substring = true)[0].performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        compose.onNodeWithText("Свернуть").assertExists()
    }
}

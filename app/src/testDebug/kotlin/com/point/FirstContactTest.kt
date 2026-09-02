package com.point

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.point.core.model.HistoryEntry
import com.point.core.model.ObjectKind
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirstContactTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `пустой дом говорит, что такое Point — глаголом и примером`() {
        compose.setContent { HomeScreen(recent = emptyList(), onOpen = {}, onSettings = {}, aiKeySet = false) }

        compose.onNodeWithText("Point прочитает его и покажет", substring = true).assertIsDisplayed()
    }

    @Test fun `дверь «Новый объект» на месте — объект есть куда дать`() {
        compose.setContent { HomeScreen(recent = emptyList(), onOpen = {}, onSettings = {}, aiKeySet = false) }

        compose.onNodeWithText("Новый объект", substring = true).assertIsDisplayed()
    }

    @Test fun `про ключ сказано и то, что работает без него`() {
        compose.setContent { HomeScreen(recent = emptyList(), onOpen = {}, onSettings = {}, aiKeySet = false) }

        compose.onNodeWithText("работают и без ключа", substring = true).assertExists()
    }

    @Test fun `главный вход в Point назван словом`() {
        compose.setContent { HomeScreen(recent = emptyList(), onOpen = {}, onSettings = {}, aiKeySet = false) }

        compose.onNodeWithText("Поделиться", substring = true).assertExists()
    }

    @Test fun `на пустом доме есть путь к примеру`() {
        compose.setContent { HomeScreen(recent = emptyList(), onOpen = {}, onSettings = {}, aiKeySet = false) }

        compose.onNodeWithText("Посмотреть на примере").assertExists()
    }

    @Test fun `пример обещает работу без ключа, сети и разрешений`() {
        compose.setContent { HomeScreen(recent = emptyList(), onOpen = {}, onSettings = {}, aiKeySet = false) }

        compose.onNodeWithText("без ключа, без сети", substring = true).assertExists()
    }

    @Test fun `тап по примеру зовёт положить пример в разбор`() {
        var opened = 0
        compose.setContent {
            HomeScreen(recent = emptyList(), onOpen = {}, onSettings = {}, aiKeySet = false, onExample = { opened++ })
        }

        compose.onNodeWithText("Посмотреть на примере").performScrollTo().performClick()

        assertEquals("строка есть, а тап по ней не делает ничего", 1, opened)
    }

    @Test fun `путь к примеру стоит ниже главной двери`() {
        compose.setContent { HomeScreen(recent = emptyList(), onOpen = {}, onSettings = {}, aiKeySet = false) }

        val newObject = compose.onNodeWithText("Новый объект").fetchSemanticsNode().positionInRoot.y
        val example = compose.onNodeWithText("Посмотреть на примере").fetchSemanticsNode().positionInRoot.y

        assertTrue("пример встал выше двери «Новый объект»", example > newObject)
    }

    @Test fun `с первым своим объектом пример уходит с экрана`() {
        val mine = listOf(
            HistoryEntry(
                id = "1",
                mime = "text/plain",
                kind = ObjectKind.TEXT,
                name = "заметка.txt",
                epochMillis = 1_700_000_000_000,
                ref = ScratchRef("/tmp/заметка.txt"),
            ),
        )
        compose.setContent { HomeScreen(recent = mine, onOpen = {}, onSettings = {}, aiKeySet = false) }

        compose.onNodeWithText("Посмотреть на примере").assertDoesNotExist()
    }
}

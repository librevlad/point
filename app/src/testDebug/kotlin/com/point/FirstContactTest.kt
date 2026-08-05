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

/**
 * Первый контакт: экран, который человек видит до всякой настройки (RC).
 *
 * Аудит перед закрытым релизом показал, что продукт нигде не говорит, что он такое: подпись звала
 * «поделиться объектом» — словом, которого человек ещё не знает, — а первым сообщением сверху стоял
 * призыв подключить чужой AI-сервис. Знакомство начиналось с рассказа о том, чего Point без ключа
 * не умеет, при том что лучшее, что он умеет, работает бесплатно и без сети.
 *
 * Эти тесты держат порядок знакомства: сначала что это такое, потом куда дать объект, потом — на
 * чём это проверить (#210), и только потом про ключ.
 */
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

        // Прежний текст называл только молчащее — и человек делал вывод, что без чужого сервиса
        // Point бесполезен. Проверяем наличие, а не видимость: строка стоит под главной дверью,
        // и на низком экране до неё нужно доскроллить — это и есть задуманный порядок знакомства.
        compose.onNodeWithText("работают и без ключа", substring = true).assertExists()
    }

    @Test fun `главный вход в Point назван словом`() {
        compose.setContent { HomeScreen(recent = emptyList(), onOpen = {}, onSettings = {}, aiKeySet = false) }

        // «Поделиться» — вход, которым нельзя воспользоваться изнутри приложения: он живёт в чужих
        // приложениях. Поэтому дверь «Новый объект» его не перечисляет, а человек без этой строки
        // не догадывается, что Point вообще так открывается (#580).
        compose.onNodeWithText("Поделиться", substring = true).assertExists()
    }

    // --- Песочница на первом запуске (#210) ---

    /**
     * Человеку без своего файла есть на чём проверить сказанное — не выходя из приложения.
     *
     * До этого первый экран делал утверждение («Point прочитает и покажет»), проверить которое
     * было нечем: объекта у человека в руках нет, а идти за ним — значит выйти из Point в момент
     * знакомства.
     */
    @Test fun `на пустом доме есть путь к примеру`() {
        compose.setContent { HomeScreen(recent = emptyList(), onOpen = {}, onSettings = {}, aiKeySet = false) }

        compose.onNodeWithText("Посмотреть на примере").assertExists()
    }

    @Test fun `пример обещает работу без ключа, сети и разрешений`() {
        compose.setContent { HomeScreen(recent = emptyList(), onOpen = {}, onSettings = {}, aiKeySet = false) }

        // Обещание проверяемое: человек, поставивший Point минуту назад, не должен упереться ни
        // в согласие, ни в отсутствие связи.
        compose.onNodeWithText("без ключа, без сети", substring = true).assertExists()
    }

    @Test fun `тап по примеру зовёт положить пример во флоу`() {
        var opened = 0
        compose.setContent {
            HomeScreen(recent = emptyList(), onOpen = {}, onSettings = {}, aiKeySet = false, onExample = { opened++ })
        }

        compose.onNodeWithText("Посмотреть на примере").performScrollTo().performClick()

        assertEquals("строка есть, а тап по ней не делает ничего", 1, opened)
    }

    /**
     * Пример не спорит за внимание с «Новым объектом»: он ниже и не светится.
     *
     * Светящаяся строка на доме одна — главное здесь принести СВОЁ. Проверяем то, что видно
     * глазами и держится кодом: порядок. Порядок и есть половина ответа на «кто из двух главный».
     */
    @Test fun `путь к примеру стоит ниже главной двери`() {
        compose.setContent { HomeScreen(recent = emptyList(), onOpen = {}, onSettings = {}, aiKeySet = false) }

        val newObject = compose.onNodeWithText("Новый объект").fetchSemanticsNode().positionInRoot.y
        val example = compose.onNodeWithText("Посмотреть на примере").fetchSemanticsNode().positionInRoot.y

        assertTrue("пример встал выше двери «Новый объект»", example > newObject)
    }

    /** «После первого своего объекта путь не навязывается» — дословно из #210. */
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

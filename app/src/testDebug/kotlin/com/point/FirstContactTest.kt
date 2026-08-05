package com.point

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
 * Эти три теста держат порядок знакомства: сначала что это такое, потом куда дать объект, и только
 * потом — про ключ.
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
}

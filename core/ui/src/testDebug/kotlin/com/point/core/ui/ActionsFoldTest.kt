package com.point.core.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
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
 * Хвост каждой группы свёрнут (#530).
 *
 * У разобранной картинки набирается порядка двадцати пяти действий, и до этого среза все они
 * стояли открытым списком в трёх разделах — ровно то ощущение «случайный набор кнопок», от
 * которого продукт уходит. Разделы перестают помогать, когда каждый из них длиной в экран.
 *
 * Проверять это чистой функцией мало: свёртка живёт на экране, и её обещание — «за этой строкой
 * ровно столько действий, сколько написано» — держится на том, что видно пальцу. Robolectric
 * поднимает настоящий Android в JVM, эмулятор не нужен, ворота те же (`./gradlew test`).
 */
@RunWith(RobolectricTestRunner::class)
class ActionsFoldTest {

    @get:Rule val compose = createComposeRule()

    private val obj = PointObject(
        id = "o",
        mime = "image/jpeg",
        uri = ScratchRef("/scratch/снимок.jpg"),
        state = ObjectState(ObjectKind.IMAGE),
        metadata = mapOf("name" to "снимок.jpg"),
    )

    private fun bubble(title: String, intent: Intent) = Bubble(
        icon = "ocr",
        title = title,
        capabilityId = CapabilityId(title),
        expectedNextState = ObjectState(ObjectKind.TEXT),
        intent = intent,
    )

    /** Восемь действий в «Извлечь» и два в «Отправить» — длинная группа и короткая рядом. */
    private val bubbles =
        (1..8).map { bubble("Извлечение $it", Intent.UNDERSTAND) } +
            listOf(bubble("Поделиться", Intent.SEND), bubble("Сохранить", Intent.SEND))

    private fun screen() = compose.setContent {
        PointTheme(darkTheme = true) {
            FirstScreen(obj = obj, bubbles = bubbles, onBubble = {})
        }
    }

    @Test fun `видно верхние действия группы, остальные — за «Ещё N»`() {
        screen()

        compose.onNodeWithText("Извлечение 1").assertExists()
        compose.onNodeWithText("Извлечение 3").assertExists()
        compose.onNodeWithText("Извлечение 4").assertDoesNotExist()
        compose.onNodeWithText("Ещё 5").assertExists()
    }

    @Test fun `число в подписи называет ровно то, что спрятано`() {
        // Обещание свёртки держится на этом числе: «Ещё 5» и пять открывшихся строк — одно и то
        // же, иначе за строкой оказывается неизвестно что.
        screen()

        compose.onNodeWithText("Ещё 5").performScrollTo().performClick()

        (1..8).forEach { compose.onNodeWithText("Извлечение $it").assertExists() }
    }

    @Test fun `раскрытое сворачивается обратно`() {
        // Иначе тап по «Ещё» был бы дверью в один конец: двадцать пять строк вернулись, и убрать
        // их можно только уйдя с экрана.
        screen()

        compose.onNodeWithText("Ещё 5").performScrollTo().performClick()
        compose.onNodeWithText("Свернуть").performScrollTo().performClick()

        compose.onNodeWithText("Извлечение 8").assertDoesNotExist()
        compose.onNodeWithText("Ещё 5").assertExists()
    }

    @Test fun `короткая группа сворачивать нечего и не предлагает`() {
        // «Отправить» из двух строк: «Ещё 0» было бы лишней строкой вместо сэкономленной.
        screen()

        compose.onNodeWithText("Поделиться").assertExists()
        compose.onNodeWithText("Сохранить").assertExists()
        compose.onNodeWithText("Ещё 0").assertDoesNotExist()
    }

    @Test fun `главное действие остаётся первым и видимым`() {
        // Свёртка режет хвост, а не голову: верхняя строка первого раздела — «Основное действие»,
        // и потерять её значило бы поменять экран местами.
        screen()

        compose.onNodeWithText("Извлечение 1").assertExists()
    }
}

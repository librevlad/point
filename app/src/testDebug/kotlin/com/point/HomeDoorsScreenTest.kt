package com.point

import com.point.core.flow.SETTINGS_TITLE
import com.point.source.SourcePickerActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Двери домашнего экрана — настоящая `HomeActivity`, настоящие нажатия (#456, #462).
 *
 * Обе находки ревью были невидимы для CI по построению: экран проверялся только глазами. Теперь
 * безымянная иконка провалит первый тест (он ищет ПОДПИСЬ, а не `contentDescription`), а
 * потерянный путь к источникам — второй.
 */
@RunWith(RobolectricTestRunner::class)
class HomeDoorsScreenTest {

    @get:Rule val compose = createAndroidComposeRule<HomeActivity>()


    @Test fun `у каждой двери домашнего экрана есть подпись`() {
        // #462: в углу стояли стрелка вниз, монитор и шестерёнка — три загадки без единого слова.
        // #544: служебных дверей стало одна, и подпись у неё та же, что заголовок за ней.
        compose.onNodeWithText("Новый объект").assertExists()
        compose.onNodeWithText(SETTINGS_TITLE).assertExists()
    }

    @Test fun `«Новый объект» ведёт к выбору источника, а не в редактор плиток`() {
        // До узла доезжаем, как пальцем: на низком экране дверь стоит ниже кромки окна.
        compose.onNodeWithText("Новый объект").performScrollTo().performClick()
        compose.waitForIdle()

        val opened = shadowOf(compose.activity).nextStartedActivity
        assertNotNull("тап по «Новый объект» не открыл ничего — источники снова недостижимы", opened)
        assertEquals(SourcePickerActivity::class.java.name, opened!!.component?.className)
    }

    // --- Песочница на первом запуске (#210) ---

    /**
     * «Ни одного нового экрана» — дословное требование #210.
     *
     * Пример открывается там же, где открывается любой объект: тот же `PointFlow` под тем же
     * `state.frame`. Чужая активити здесь — признак того, что песочница успела завести себе
     * собственное место, а этого ей не разрешено.
     */
    @Test fun `путь к примеру никуда не уводит из Point`() {
        compose.onNodeWithText("Посмотреть на примере").performScrollTo().performClick()
        compose.waitForIdle()

        assertNull("пример увёл человека на отдельный экран", shadowOf(compose.activity).nextStartedActivity)
    }

    /**
     * Пример должен быть В СБОРКЕ, а не в намерениях: строка на экране без байтов за ней — это
     * тупик, который человек найдёт первым же тапом.
     */
    @Test fun `пример лежит в сборке настоящим снимком`() {
        val bytes = compose.activity.resources.openRawResource(R.raw.example_card).use { it.readBytes() }

        assertTrue("пример пуст или выпал из сборки", bytes.size > 10_000)
        // JPEG начинается маркером SOI: подменённый на заглушку файл провалится здесь, а не на
        // телефоне у человека, который только что поставил Point.
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
    }
}

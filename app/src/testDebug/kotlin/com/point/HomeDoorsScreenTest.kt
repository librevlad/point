package com.point

import com.point.core.flow.SETTINGS_TITLE
import com.point.source.SourcePickerActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}

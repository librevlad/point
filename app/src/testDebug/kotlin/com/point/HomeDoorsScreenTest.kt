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

@RunWith(RobolectricTestRunner::class)
class HomeDoorsScreenTest {

    @get:Rule val compose = createAndroidComposeRule<HomeActivity>()

    @Test fun `у каждой двери домашнего экрана есть подпись`() {

        compose.onNodeWithText("Новый объект").assertExists()
        compose.onNodeWithText(SETTINGS_TITLE).assertExists()
    }

    @Test fun `«Новый объект» ведёт к выбору источника, а не в редактор плиток`() {

        compose.onNodeWithText("Новый объект").performScrollTo().performClick()
        compose.waitForIdle()

        val opened = shadowOf(compose.activity).nextStartedActivity
        assertNotNull("тап по «Новый объект» не открыл ничего — источники снова недостижимы", opened)
        assertEquals(SourcePickerActivity::class.java.name, opened!!.component?.className)
    }

    @Test fun `путь к примеру никуда не уводит из Point`() {
        compose.onNodeWithText("Посмотреть на примере").performScrollTo().performClick()
        compose.waitForIdle()

        assertNull("пример увёл человека на отдельный экран", shadowOf(compose.activity).nextStartedActivity)
    }

    @Test fun `пример лежит в сборке настоящим снимком`() {
        val bytes = compose.activity.resources.openRawResource(R.raw.example_card).use { it.readBytes() }

        assertTrue("пример пуст или выпал из сборки", bytes.size > 10_000)

        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
    }
}

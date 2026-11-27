package com.point

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.point.core.flow.AiTrialOffer
import com.point.core.model.ObjectKind
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Где Point просит ключ, он показывает и путь короче ключа — пробу через вход (#477).
 *
 * Новичок без ключа и без входа видел только «нужен ключ», хотя вход даёт AI-действия даром:
 * самый короткий путь был спрятан ровно там, где человек упёрся.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class TrialOfferOnKeyScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun keyScreen(trial: AiTrialOffer?, onSignIn: () -> Unit = {}) = compose.setContent {
        PointTheme(darkTheme = true) {
            KeyScreen(
                screen = aiKeysScreenOf(),
                errand = KeyErrand(action = "Понять", objectName = "снимок", kind = ObjectKind.IMAGE),
                onSave = {},
                onCancel = {},
                aiTrial = trial,
                onSignIn = onSignIn,
            )
        }
    }

    @Test fun `кто не вошёл, видит вход ради пробы, и нажатие ведёт ко входу`() {
        var signIn = false
        keyScreen(AiTrialOffer(signedIn = false, left = 30, trial = 30)) { signIn = true }

        compose.onNodeWithText("AI-действий без ключа", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("AI-действий без ключа", substring = true).performClick()

        assertTrue("нажатие на предложение не повело ко входу", signIn)
    }

    @Test fun `вошедший видит остаток пробы`() {
        keyScreen(AiTrialOffer(signedIn = true, left = 7, trial = 30))

        compose.onNodeWithText("осталось: 7", substring = true).performScrollTo().assertIsDisplayed()
    }
}

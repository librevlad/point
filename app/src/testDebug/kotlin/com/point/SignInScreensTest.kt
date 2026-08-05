package com.point

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.point.core.flow.CircleDevice
import com.point.core.flow.DeviceKind
import com.point.core.flow.PointAccount
import com.point.core.flow.SIGN_IN_ACTION
import com.point.core.flow.SIGN_IN_TITLE
import com.point.core.flow.SignIn
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Вход и круг устройств — под экранным тестом (#472, #473).
 *
 * Ловится ровно то, что руками ловится только с телефоном в руках: **дверь стоит перед объектом**
 * (иначе вход был бы декорацией), у ожидания есть выход, отказ говорит словами, а «Отключить»
 * отключает именно то устройство, на строке которого нажали. Всё это — узлы, по которым человек
 * нажимает; молча потерянный колбэк здесь выглядел бы как «красиво и ничего не делает».
 */
@RunWith(RobolectricTestRunner::class)
class SignInScreensTest {

    @get:Rule val compose = createComposeRule()

    private val account = PointAccount("d1", "tok", "me@example.com", "Pixel", DeviceKind.PHONE)

    private fun signIn(state: SignIn, onSignIn: () -> Unit = {}, onCancel: () -> Unit = {}) {
        compose.setContent {
            PointTheme(darkTheme = true) {
                SignInScreen(state = state, onSignIn = onSignIn, onCancel = onCancel)
            }
        }
    }

    @Test fun `не вошли — одна строка смысла и одна кнопка`() {
        var started = false
        signIn(SignIn.SignedOut, onSignIn = { started = true })

        compose.onNodeWithText(SIGN_IN_TITLE).assertExists()
        compose.onNodeWithText(SIGN_IN_ACTION).performClick()

        assertTrue("единственная кнопка экрана перестала начинать вход", started)
    }

    @Test fun `экран входа не оправдывается`() {
        signIn(SignIn.SignedOut)

        // Строка «Что вы пересылаете между своими устройствами, сервер прочитать не может»
        // отвечала на возражение, которого человек не высказывал, — и тем самым его заводила:
        // шесть моделей из шести прочли её как оправдание, а не как спокойствие (#580).
        // Обещания о данных живут там, где человек их ищет: на /privacy и в согласии.
        listOf("прочитать не может", "видит только", "не может прочитать").forEach { phrase ->
            compose.onNodeWithText(phrase, substring = true)
                .assertDoesNotExist()
        }
    }

    @Test fun `заголовок входа говорит, что человек получит`() {
        signIn(SignIn.SignedOut)

        // Прежний заголовок описывал устройство продукта («Point узнаёт ваши устройства по
        // аккаунту»). На этот экран человек попадает сам и спрашивает одно: зачем входить.
        compose.onNodeWithText("узнаёт ваши устройства", substring = true).assertDoesNotExist()
        compose.onNodeWithText("увидят друг друга", substring = true).assertExists()
    }

    @Test fun `пока человек в браузере, на экране код и выход`() {
        var cancelled = false
        signIn(
            SignIn.Waiting(loginId = "l1", code = "K7-42Q", url = "https://point.example/login"),
            onCancel = { cancelled = true },
        )

        // Код виден там, где его сверяют, — рядом с ожиданием, а не в справке.
        compose.onNodeWithText("Подтвердите вход в браузере · код K7-42Q").assertExists()
        compose.onNodeWithText("Отменить").performClick()

        assertTrue("ожидание без выхода — тупик", cancelled)
    }

    @Test fun `отказ говорит, что случилось и что делать, и дверь остаётся открытой`() {
        var retried = false
        signIn(
            SignIn.Refused("До сервера Point не дозвониться", "Проверьте интернет и попробуйте ещё раз."),
            onSignIn = { retried = true },
        )

        compose.onNodeWithText("До сервера Point не дозвониться").assertExists()
        compose.onNodeWithText("Проверьте интернет и попробуйте ещё раз.").assertExists()
        compose.onNodeWithText(SIGN_IN_ACTION).performClick()

        assertTrue("после отказа входить стало нечем", retried)
    }

    @Test fun `вошли — человек это видит, а не догадывается по исчезновению экрана`() {
        signIn(SignIn.SignedIn(account))

        compose.onNodeWithText("Вы вошли").assertExists()
        compose.onNodeWithText("me@example.com").assertExists()
    }

    @Test fun `дверь входа стоит ПЕРЕД объектом, а не рядом с ним`() {
        // Главная проверка среза: пока Point не знает, чьё это устройство, круга нет — и объекту
        // место за дверью. Объект при этом не теряется: он ждёт под экраном.
        val obj = PointObject("o", "text/plain", ScratchRef("/o"), ObjectState(ObjectKind.TEXT))
        compose.setContent {
            PointTheme(darkTheme = true) {
                PointHost(
                    state = FlowUiState(
                        frame = FlowFrame(obj, emptyList()),
                        signIn = SignIn.SignedOut,
                    ),
                    onBubble = {},
                    onSubmitInput = {},
                    onCancelInput = {},
                )
            }
        }

        compose.onNodeWithText(SIGN_IN_TITLE).assertExists()
    }

    // --- «Мои устройства» ---

    private fun devices(
        state: DevicesScreenState,
        onRevoke: (String) -> Unit = {},
        onSignOut: () -> Unit = {},
    ) {
        compose.setContent {
            PointTheme(darkTheme = true) {
                MyDevicesScreen(
                    state = state,
                    onRevoke = onRevoke,
                    onSignOut = onSignOut,
                    onClose = {},
                    now = NOW,
                )
            }
        }
    }

    @Test fun `круг показывает вид устройства и время последнего контакта`() {
        devices(
            DevicesScreenState(
                email = "me@example.com",
                loading = false,
                devices = listOf(
                    CircleDevice("d1", DeviceKind.PHONE, "Pixel 8", NOW - 20_000, self = true),
                    CircleDevice("d2", DeviceKind.PC, "Рабочий ноутбук", NOW - 30 * 60 * 60_000L),
                ),
            ),
        )

        compose.onNodeWithText("Pixel 8").assertExists()
        // «Это устройство» стоит первым, чтобы человек не отключил то, что держит в руках.
        compose.onNodeWithText("это устройство · Телефон · на связи").assertExists()
        compose.onNodeWithText("Компьютер · вчера").assertExists()
    }

    @Test fun `«Отключить» отключает именно то устройство, на строке которого нажали`() {
        var revoked: String? = null
        devices(
            DevicesScreenState(
                loading = false,
                devices = listOf(
                    CircleDevice("d1", DeviceKind.PHONE, "Pixel 8", NOW, self = true),
                    CircleDevice("d2", DeviceKind.PC, "Рабочий ноутбук", NOW),
                ),
            ),
            onRevoke = { revoked = it },
        )

        compose.onAllNodesWithText("Отключить")[1].performClick()

        assertEquals("d2", revoked)
    }

    @Test fun `один в круге — сказано, чем это лечится`() {
        var signedOut = false
        devices(
            DevicesScreenState(
                loading = false,
                devices = listOf(CircleDevice("d1", DeviceKind.PHONE, "Pixel 8", NOW, self = true)),
            ),
            onSignOut = { signedOut = true },
        )

        compose.onNodeWithText("ПОКА ВЫ ОДИН").assertExists()
        compose.onNodeWithText("Выйти").performScrollTo().performClick()

        assertTrue("«Выйти» перестал выходить", signedOut)
    }

    @Test fun `пока круг едет, экран говорит об этом, а не показывает пустоту`() {
        devices(DevicesScreenState(email = "me@example.com", loading = true))

        compose.onNodeWithText("Спрашиваю сервер о ваших устройствах…").assertExists()
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}

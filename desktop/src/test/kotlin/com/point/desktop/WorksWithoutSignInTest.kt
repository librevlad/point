package com.point.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.point.core.flow.AccountClient
import com.point.core.flow.AccountStore
import com.point.core.flow.BrowserOpener
import com.point.core.flow.CircleAnswer
import com.point.core.flow.CircleDevice
import com.point.core.flow.DeviceKeyPair
import com.point.core.flow.DeviceKeyStore
import com.point.core.flow.DeviceKind
import com.point.core.flow.LinkState
import com.point.core.flow.LoginPoll
import com.point.core.flow.LoginStart
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PointAccount
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.desktop.ui.DEVICES_NOT_LINKED
import com.point.desktop.ui.PointWindow
import com.point.desktop.ui.linkLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Point на компьютере работает без входа; вход нужен, чтобы подключить устройства (решение
 * владельца 20.10.2026: «дай работать без входа, но для подключения телефона или планшета (а их
 * может быть и несколько) или другого ПК нужен вход»).
 *
 * Прежде окно без аккаунта открывалось экраном входа и дальше не пускало, а «Отменить» и выход
 * из аккаунта возвращали туда же — при словах на том же экране «для работы на одном устройстве
 * вход не нужен».
 */
@OptIn(ExperimentalTestApi::class)
class WorksWithoutSignInTest {

    @Test
    fun `без аккаунта окно открывается на работе, а не на входе`() = runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
        val state = desktopState()
        showCompact {
            PointWindow(
                state = state,
                config = PcConfig(name = "Компьютер"),
                account = account(stored = null),
                openObject = MutableStateFlow(null),
                onObjectOpened = {},
            )
        }

        val seen = sceneNodes()
        assertTrue("без входа нет «Нового объекта» — окно снова заперто: ${seen.visibleText()}", seen.shows("Новый объект"))
        assertTrue("без входа строка связи не говорит, что устройства не подключены: ${seen.visibleText()}", seen.shows(DEVICES_NOT_LINKED))
        assertFalse(
            "без входа окно снова открылось экраном входа: ${seen.visibleText()}",
            seen.shows(com.point.core.flow.SIGN_IN_ACTION),
        )
    }

    @Test
    fun `экран входа появляется, только когда вход начали, и отмена возвращает назад`() {
        val account = account(stored = null)
        assertNull("вход начался сам, без человека", account.signIn.value)

        account.cancel()
        assertNull("отмена оставила человека на экране входа", account.signIn.value)
    }

    /** Вход, который не удался, не запирает окно: из него есть дорога назад. */
    @Test
    fun `из несостоявшегося входа есть дорога назад`() = runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
        var back = false
        showCompact {
            com.point.desktop.ui.SignInPane(
                state = com.point.core.flow.SignIn.Refused(what = "Сервер не ответил", fix = "Попробуйте ещё раз"),
                onSignIn = {},
                onCancel = { back = true },
                onOpenAgain = {},
                onContinue = {},
            )
        }

        onNodeWithText("Не сейчас").performClick()
        assertTrue("из отказа входа не уйти — окно снова заперто", back)
    }

    @Test
    fun `без входа не зовут поделиться с телефона`() {
        assertFalse("без входа обещан телефон, которого нет", "телефон" in com.point.desktop.ui.howToBring(linked = false))
        assertTrue("со входом пропала дорога с телефона", "телефон" in com.point.desktop.ui.howToBring(linked = true))
    }

    @Test
    fun `выход из аккаунта возвращает к работе без устройств, а не на экран входа`() {
        val account = account(stored = signedIn)
        assertTrue("аккаунт с диска не прочитан — проверять выход не на чем", account.account.value != null)

        account.signOut()

        assertNull("после выхода аккаунт остался", account.account.value)
        assertNull("после выхода окно снова заперто экраном входа", account.signIn.value)
        assertNull("выход по просьбе человека назван бедой", account.error.value)
    }

    @Test
    fun `отключение с другого устройства сказано словами`() {
        val account = account(stored = signedIn, circle = CircleAnswer.Revoked)

        account.refreshCircle()

        assertNull("отключённый компьютер всё ещё считает себя в круге", account.account.value)
        assertEquals(
            "компьютер остался без своих устройств молча",
            com.point.core.flow.PC_DEVICE_REVOKED,
            account.error.value,
        )
    }

    @Test
    fun `без связанных устройств телефонные действия не предлагаются`() {
        val call = PcRemoteAction("call", "Позвонить", kinds = setOf("TEXT"), priority = 10)
        fun phoneActions(linked: Boolean): List<String> {
            val state = DesktopState(
                registry = DesktopRegistry(emptySet()),
                resolver = DesktopResolver(emptySet()),
                clipboard = { },
                linked = { linked },
            ).apply { setPhoneCaps(listOf(call), persist = false) }
            return state.actionsFor(textArrival(id = "счёт")).filter { it.onPhone }.map { it.title }
        }

        assertEquals("со связанным телефоном его действие пропало", listOf(call.label), phoneActions(linked = true))
        assertTrue("без входа предложено действие телефона, которого нет", phoneActions(linked = false).isEmpty())
    }

    @Test
    fun `«На телефон» без входа не предлагается, со входом — на месте`() {
        fun offered(linked: Boolean): Boolean {
            val resolver = DesktopResolver(setOf(PcToPhoneRealizer(Outbox(kotlin.io.path.createTempDirectory("outbox-").toFile()), linked = { linked })))
            val registry = DesktopRegistry(setOf(PcToPhoneCapability()), runnable = resolver::canRun)
            return registry.bubblesFor(ObjectState(ObjectKind.TEXT)).any { it.capabilityId == CapabilityId(PC_TO_PHONE) }
        }

        assertTrue("со входом «На телефон» пропало", offered(linked = true))
        assertFalse("без входа предложено отправить на телефон, которого нет", offered(linked = false))
    }

    @Test
    fun `строка связи называет одно устройство по имени, а несколько — устройствами`() {
        val link = LinkState.Live(0)
        val tablet = CircleDevice(id = "т-1", name = "Планшет Ани", kind = DeviceKind.PHONE, self = false)
        val laptop = CircleDevice(id = "к-2", name = "Ноутбук", kind = DeviceKind.PC, self = false)

        assertEquals(DEVICES_NOT_LINKED, linkLine(signedIn = false, others = listOf(tablet), link = link))
        assertTrue("одно устройство не названо своим именем", linkLine(true, listOf(tablet), link).startsWith(tablet.name))

        val many = linkLine(true, listOf(tablet, laptop), link)
        assertFalse("из нескольких устройств строка назвала одно: $many", tablet.name in many || laptop.name in many)
        assertFalse("несколько устройств названы телефоном: $many", "телефон" in many)
    }

    private val signedIn = PointAccount(
        deviceId = "пк-1",
        deviceToken = "пропуск",
        email = "человек@example.org",
        deviceName = "Компьютер",
        kind = DeviceKind.PC,
    )

    /** Аккаунт компьютера с тем, что лежит на диске, и сервером, отвечающим [circle]. */
    private fun account(stored: PointAccount?, circle: CircleAnswer = CircleAnswer.Unreachable) = DesktopAccount(
        scope = CoroutineScope(Dispatchers.Unconfined),
        store = object : AccountStore {
            private var held: PointAccount? = stored

            override fun current(): PointAccount? = held

            override suspend fun save(account: PointAccount) { held = account }

            override suspend fun clear() { held = null }
        },
        client = object : AccountClient {
            override suspend fun start(deviceName: String, kind: DeviceKind): LoginStart? = null

            override suspend fun poll(loginId: String, claimToken: String): LoginPoll = LoginPoll.Silent

            override suspend fun enroll(account: PointAccount, publicKey: String) = false

            override suspend fun circle(account: PointAccount) = circle

            override suspend fun revoke(account: PointAccount, deviceId: String) = true

            override suspend fun deleteAccount(account: PointAccount) = false
        },
        browser = BrowserOpener { },
        deviceName = "Компьютер",
        keys = object : DeviceKeyStore {
            override fun keys() = DeviceKeyPair(privateKey = "", publicKey = "")
        },
    )
}

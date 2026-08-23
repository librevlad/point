/**
 * Живое окно компакта для тестов, идущих путём человека (#1019).
 *
 * Собрано в одном месте: сцена окна, кадры, Esc, чистое состояние ПК, уже вошедший человек и
 * вопросы к первому кадру — что человек видит и не вылезло ли что за окно (#1250).
 * Прежде каждый такой тест носил свою копию этих помощников — вплоть до комментария, — и
 * новый сюжет начинался с переписывания чужого файла.
 */
@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.test.ExperimentalTestApi::class,
)

package com.point.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.point.core.flow.AccountClient
import com.point.core.flow.AccountStore
import com.point.core.flow.BrowserOpener
import com.point.core.flow.CircleAnswer
import com.point.core.flow.DeviceKeyPair
import com.point.core.flow.DeviceKeyStore
import com.point.core.flow.DeviceKind
import com.point.core.flow.LoginPoll
import com.point.core.flow.LoginStart
import com.point.core.flow.PointAccount
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.desktop.ui.CompactApp
import com.point.desktop.ui.PointColors
import com.point.desktop.ui.PointDesktopTheme
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

/** Окно компакта целиком, как его собирает `Main`: список ⇄ объект, Esc — шаг назад. */
fun compactScene(
    state: DesktopState,
    onHide: () -> Unit,
    account: DesktopAccount = signedInAccount(),
): ImageComposeScene = ImageComposeScene(
    width = COMPACT_WIDTH,
    height = COMPACT_HEIGHT,
    density = Density(1f),
) {
    PointDesktopTheme {
        CompactApp(
            state = state,
            config = PcConfig(name = "Компьютер"),
            account = account,
            openObject = MutableStateFlow(null),
            onObjectOpened = {},
            onHide = onHide,
        )
    }
}

/**
 * Несколько кадров подряд: ответ окна — это цепочка (состояние → перерисовка → эффект),
 * и одним кадром она не проходит.
 */
fun ImageComposeScene.frames(count: Int = 5) {
    repeat(count) { render(nextFrameNanos()) }
}

/** Время кадра обязано расти: сцена сравнивает его со своим прошлым. */
private val frameClock = AtomicLong(0)

private fun nextFrameNanos(): Long = frameClock.addAndGet(16_000_000L)

/** Esc — та самая клавиша, которую жмёт человек. */
fun escapeKey() = KeyEvent(key = Key.Escape, type = KeyEventType.KeyDown)

/**
 * Компьютер без прошлого: журнал пуст, действий не зарегистрировано.
 *
 * Журналом можно дать компьютеру прошлое — то, что человек увидит в «Раньше» (#1250).
 */
fun desktopState(journal: List<JournalEntry> = emptyList()) = DesktopState(
    registry = DesktopRegistry(emptySet()),
    resolver = DesktopResolver(emptySet()),
    clipboard = { },
    journalStore = object : JournalStore {
        override fun load(): List<JournalEntry> = journal

        override fun save(entries: List<JournalEntry>) = Unit
    },
)

/** Объект, каким он доходит до окна: настоящий файл рядом и человеческое имя. */
fun textArrival(id: String, name: String = "Счёт 4411"): InboxItem {
    val file = File.createTempFile("$id-", ".txt").apply {
        writeText("Оплатите счёт 4411 до 26.04.2026.")
        deleteOnExit()
    }
    return InboxItem(
        PointObject(
            id = id,
            mime = "text/plain",
            uri = ScratchRef(file.absolutePath),
            state = ObjectState(ObjectKind.TEXT),
            metadata = mapOf("name" to name),
        ),
    )
}

/** Человек за компьютером уже вошёл — иначе окно показывает вход, а не список. */
fun signedInAccount() = DesktopAccount(
    scope = CoroutineScope(Dispatchers.Unconfined),
    store = object : AccountStore {
        private var held: PointAccount? = PointAccount(
            deviceId = "пк-1",
            deviceToken = "пропуск",
            email = "человек@example.org",
            deviceName = "Компьютер",
            kind = DeviceKind.PC,
        )

        override fun current(): PointAccount? = held

        override suspend fun save(account: PointAccount) { held = account }

        override suspend fun clear() { held = null }
    },
    client = object : AccountClient {
        override suspend fun start(deviceName: String, kind: DeviceKind): LoginStart? = null

        override suspend fun poll(loginId: String, claimToken: String): LoginPoll = LoginPoll.Silent

        override suspend fun enroll(account: PointAccount, publicKey: String) = false

        override suspend fun circle(account: PointAccount) = CircleAnswer.Unreachable

        override suspend fun revoke(account: PointAccount, deviceId: String) = false

        override suspend fun deleteAccount(account: PointAccount) = false
    },
    browser = BrowserOpener { },
    deviceName = "Компьютер",
    keys = object : DeviceKeyStore {
        override fun keys() = DeviceKeyPair(privateKey = "", publicKey = "")
    },
)

/**
 * Окно компьютера под тестом (#1250): сцена собирается в размере компакта, и у неё можно
 * спросить, что человек видит в первом кадре.
 *
 * Раньше раскладку окна не проверял никто: соседний сторож (`SettingsRowKeepsWidthTest`)
 * оправдывал чтение исходника словами «проверить раскладку Compose Desktop без окна нечем».
 * Есть чем — деревом семантики той же сцены, что рисуется человеку.
 */
internal fun SkikoComposeUiTest.showCompact(content: @Composable () -> Unit) {

    // Время двигаем сами: портал дышит бесконечно (`ObjectScene`, rememberInfiniteTransition),
    // и ожидание покоя не кончилось бы никогда. Плашки действий появляются за доли секунды —
    // сдвигаем ровно на их появление и смотрим на установившийся первый кадр.
    mainClock.autoAdvance = false
    setContent {
        PointDesktopTheme {
            Box(Modifier.fillMaxSize().background(PointColors.window)) { content() }
        }
    }
    mainClock.advanceTimeBy(APPEAR_MS)
}

/** Все узлы сцены — от корня вглубь. */
internal fun SkikoComposeUiTest.sceneNodes(): List<SemanticsNode> =
    branch(onRoot(useUnmergedTree = true).fetchSemanticsNode())

private fun branch(node: SemanticsNode): List<SemanticsNode> =
    listOf(node) + node.children.flatMap(::branch)

/** Текст узла — то, что на нём написано. */
internal fun SemanticsNode.texts(): List<String> = config
    .firstOrNull { it.key == SemanticsProperties.Text }
    ?.value
    ?.let { it as? List<*> }
    ?.mapNotNull { (it as? AnnotatedString)?.text }
    .orEmpty()

/**
 * Строки, которые человек и правда видит без прокрутки: узел, уехавший под сгиб, обрезан
 * родителем до пустоты — на экране его нет, сколько бы текста в нём ни лежало.
 */
internal fun List<SemanticsNode>.visibleText(): List<String> =
    filterNot { it.boundsInRoot.isEmpty }.flatMap { it.texts() }

internal fun List<SemanticsNode>.shows(what: String): Boolean = visibleText().any { what in it }

/**
 * Узлы шире окна. Меряется размер разметки, а не видимые границы: обрезанный родителем узел
 * показал бы ровно ширину окна, и вылезшее за край было бы не отличить от помещающегося.
 */
internal fun List<SemanticsNode>.tooWide(limit: Int): List<String> =
    filter { it.size.width > limit }.map { it.texts().firstOrNull() ?: "узел ${it.size.width}" }

/** Столько длится появление плашек действий; дальше сцена стоит. */
private const val APPEAR_MS = 1_000L

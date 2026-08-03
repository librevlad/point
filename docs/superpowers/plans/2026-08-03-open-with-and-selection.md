# «Открыть с помощью Point» и выделение без чтения — план работ

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Point появляется в «Открыть с помощью» для любого файла, а область на картинке можно обвести не распознавая её.

**Architecture:** Разбор входящего intent выносится в чистую функцию без Android-типов — она тестируется на JVM и обслуживает все двери сразу. Общий экран флоу переезжает в базовый класс `FlowHostActivity`, каждой двери остаётся свой разбор. Выделение перестаёт требовать слой слов: доступ к картинке уходит за контракт `SelectionFrames`, а решение «что делает тап по объекту» становится чистой функцией.

**Tech Stack:** Kotlin, Compose, Hilt, JUnit4 (`:app` тестируется на JVM, без Robolectric).

## Global Constraints

- `:core:model` и `:core:flow` остаются Android-free — ничего из этого плана туда не попадает.
- Каждый side-effect за интерфейсом; в тестах — fakes, не моки-библиотеки.
- Backtick-имена тестов не содержат `:` и `;` — компилятор Kotlin на Windows их не принимает; вместо них тире.
- Один класс тестов гоняется так: `./gradlew :app:testDebugUnitTest --tests "com.point.ИМЯ"` (задача `:app:test` не принимает `--tests`).
- Перед сборкой в шелле: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`.
- Полная проверка — `./gradlew test assembleDebug`.
- Исход действия не врёт: отказ говорит словами, отмена знака не имеет (правило #358).

---

### Task 1: Чистый разбор входящего intent

**Files:**
- Create: `app/src/main/kotlin/com/point/Incoming.kt`
- Test: `app/src/test/kotlin/com/point/IncomingTest.kt`
- Modify: `app/src/main/kotlin/com/point/ShareActivity.kt:105-127` (метод `handleShare`)

**Interfaces:**
- Produces: `sealed interface Incoming` с `Incoming.Single(uri: String, mime: String)`, `Incoming.Many(uris: List<String>)`, `Incoming.Body(text: String)`; функция `incomingOf(action: String?, type: String?, data: String?, stream: String?, text: String?, streams: List<String>): Incoming?`; константа `DEFAULT_MIME = "application/octet-stream"`.

- [ ] **Step 1: Написать падающий тест**

Создать `app/src/test/kotlin/com/point/IncomingTest.kt`:

```kotlin
package com.point

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Разбор входящего intent — чистая функция без единого Android-типа, поэтому судится на JVM.
 * Двери (Share, «Открыть с помощью», правый клик по тексту) отличаются только тем, что кладут
 * в эти аргументы.
 */
class IncomingTest {

    @Test
    fun `шаринг файла — объект по ссылке и своим типом`() {
        val incoming = incomingOf(
            action = "android.intent.action.SEND",
            type = "image/jpeg",
            data = null,
            stream = "content://media/1",
            text = null,
            streams = emptyList(),
        )
        assertEquals(Incoming.Single("content://media/1", "image/jpeg"), incoming)
    }

    @Test
    fun `шаринг текста без файла — тело, а не ссылка`() {
        val incoming = incomingOf(
            action = "android.intent.action.SEND",
            type = "text/plain",
            data = null,
            stream = null,
            text = "привет",
            streams = emptyList(),
        )
        assertEquals(Incoming.Body("привет"), incoming)
    }

    @Test
    fun `шаринг ни с чем — ничего, а не пустой объект`() {
        val incoming = incomingOf(
            action = "android.intent.action.SEND",
            type = "text/plain",
            data = null,
            stream = null,
            text = "",
            streams = emptyList(),
        )
        assertNull(incoming)
    }

    @Test
    fun `множественный шаринг — список ссылок`() {
        val incoming = incomingOf(
            action = "android.intent.action.SEND_MULTIPLE",
            type = "image/jpeg",
            data = null,
            stream = null,
            text = null,
            streams = listOf("content://media/1", "content://media/2"),
        )
        assertEquals(Incoming.Many(listOf("content://media/1", "content://media/2")), incoming)
    }

    @Test
    fun `открыть с помощью — объект берётся из data, а не из потока`() {
        val incoming = incomingOf(
            action = "android.intent.action.VIEW",
            type = "application/pdf",
            data = "content://downloads/7",
            stream = null,
            text = null,
            streams = emptyList(),
        )
        assertEquals(Incoming.Single("content://downloads/7", "application/pdf"), incoming)
    }

    @Test
    fun `тип неизвестен — общий тип вместо падения`() {
        val incoming = incomingOf(
            action = "android.intent.action.VIEW",
            type = null,
            data = "content://downloads/7",
            stream = null,
            text = null,
            streams = emptyList(),
        )
        assertEquals(Incoming.Single("content://downloads/7", DEFAULT_MIME), incoming)
    }

    @Test
    fun `чужое действие — ничего`() {
        val incoming = incomingOf(
            action = "android.intent.action.MAIN",
            type = null,
            data = null,
            stream = null,
            text = null,
            streams = emptyList(),
        )
        assertNull(incoming)
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `./gradlew :app:testDebugUnitTest --tests "com.point.IncomingTest"`
Expected: FAIL — `Unresolved reference: incomingOf`.

- [ ] **Step 3: Написать минимальную реализацию**

Создать `app/src/main/kotlin/com/point/Incoming.kt`:

```kotlin
package com.point

/**
 * Что пришло в дверь Point. Разбор намеренно не знает ни одного Android-типа: дверь достаёт
 * куски из своего `Intent`, а решение «объект это или ничего» принимается здесь — и потому
 * судится юнит-тестом, а не руками на устройстве.
 */
sealed interface Incoming {
    /** Один объект по ссылке: шаринг файла и «Открыть с помощью» приходят сюда одинаково. */
    data class Single(val uri: String, val mime: String) : Incoming

    /** Несколько объектов сразу (мульти-шаринг). */
    data class Many(val uris: List<String>) : Incoming

    /** Текст пришёл телом intent, файла за ним нет — дверь сама положит его в scratch. */
    data class Body(val text: String) : Incoming
}

/** Тип, когда система его не назвала: врать конкретным типом нельзя, признаки соврут следом. */
const val DEFAULT_MIME = "application/octet-stream"

fun incomingOf(
    action: String?,
    type: String?,
    data: String?,
    stream: String?,
    text: String?,
    streams: List<String> = emptyList(),
): Incoming? = when (action) {
    "android.intent.action.SEND" -> when {
        stream != null -> Incoming.Single(stream, type ?: DEFAULT_MIME)
        !text.isNullOrEmpty() -> Incoming.Body(text)
        else -> null
    }

    "android.intent.action.SEND_MULTIPLE" ->
        streams.takeIf { it.isNotEmpty() }?.let(Incoming::Many)

    "android.intent.action.VIEW" ->
        data?.let { Incoming.Single(it, type ?: DEFAULT_MIME) }

    else -> null
}
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `./gradlew :app:testDebugUnitTest --tests "com.point.IncomingTest"`
Expected: PASS, 7 тестов.

- [ ] **Step 5: Перевести ShareActivity на общий разбор**

Заменить метод `handleShare` в `app/src/main/kotlin/com/point/ShareActivity.kt` целиком на:

```kotlin
    private fun handleShare(intent: Intent) {
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val streams = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        when (
            val incoming = incomingOf(
                action = intent.action,
                type = intent.type,
                data = intent.data?.toString(),
                stream = stream?.toString(),
                text = intent.getStringExtra(Intent.EXTRA_TEXT),
                streams = streams?.map { it.toString() }.orEmpty(),
            )
        ) {
            is Incoming.Single -> viewModel.onShared(incoming.uri, incoming.mime)
            is Incoming.Many -> viewModel.onSharedMultiple(incoming.uris)
            is Incoming.Body -> {
                val uri = Uri.fromFile(cacheTextFile(cacheDir, incoming.text))
                viewModel.onShared(uri.toString(), "text/plain")
            }
            null -> Unit
        }
    }
```

- [ ] **Step 6: Проверить, что ничего не сломалось**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Коммит**

```bash
git add app/src/main/kotlin/com/point/Incoming.kt app/src/test/kotlin/com/point/IncomingTest.kt app/src/main/kotlin/com/point/ShareActivity.kt
git commit -m "refactor: разбор входящего intent стал чистой функцией и судится тестом (part of #249)"
```

---

### Task 2: «Открыть с помощью Point» для любого файла

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:39-59` (intent-filter'ы `ShareActivity`)
- Modify: `app/src/main/kotlin/com/point/ShareActivity.kt` (метод `handleShare` из задачи 1)
- Test: `app/src/test/kotlin/com/point/IncomingTest.kt` (дописывается)

**Interfaces:**
- Consumes: `incomingOf(...)`, `Incoming.Single` из задачи 1.
- Produces: ничего нового в коде; наружу — сам факт, что `ShareActivity` принимает `ACTION_VIEW`.

- [ ] **Step 1: Написать падающий тест на подстановку типа дверью**

Дописать в `app/src/test/kotlin/com/point/IncomingTest.kt`:

```kotlin
    @Test
    fun `открыть с помощью — тип от системы важнее пустого типа intent`() {
        // Дверь спрашивает тип у ContentResolver, когда intent молчит, и передаёт его сюда.
        val incoming = incomingOf(
            action = "android.intent.action.VIEW",
            type = "application/zip",
            data = "content://downloads/9",
            stream = null,
            text = null,
            streams = emptyList(),
        )
        assertEquals(Incoming.Single("content://downloads/9", "application/zip"), incoming)
    }
```

- [ ] **Step 2: Запустить — тест проходит сразу**

Run: `./gradlew :app:testDebugUnitTest --tests "com.point.IncomingTest"`
Expected: PASS. Тест закрепляет договор двери; поведение уже есть с задачи 1.

- [ ] **Step 3: Объявить дверь в манифесте**

В `app/src/main/AndroidManifest.xml`, внутри `<activity android:name=".ShareActivity" …>`, после существующего `intent-filter` для `SEND_MULTIPLE`, добавить:

```xml
            <!--
              «Открыть с помощью Point» (#249): дверь не для «Поделиться», а для «Открыть».
              Тип `*/*` — решение владельца: Point виден для любого файла. На незнакомом типе
              экран не пустой — поделиться, сохранить и «открыть в другом приложении» принимают
              что угодно.
            -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="content" />
                <data android:mimeType="*/*" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="file" />
                <data android:mimeType="*/*" />
            </intent-filter>
```

- [ ] **Step 4: Добрать тип у системы, когда intent молчит**

В `app/src/main/kotlin/com/point/ShareActivity.kt` в методе `handleShare` заменить строку `type = intent.type,` на:

```kotlin
                // При «Открыть с помощью» intent часто без типа: спрашиваем систему, иначе
                // объект приедет как «неизвестно что» и потеряет половину действий.
                type = intent.type ?: intent.data?.let { contentResolver.getType(it) },
```

- [ ] **Step 5: Собрать и поставить на эмулятор**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew test assembleDebug
"$ANDROID_HOME/platform-tools/adb.exe" install -r -t app/build/outputs/apk/debug/app-debug.apk
```
Expected: BUILD SUCCESSFUL, Success.

- [ ] **Step 6: Проверить дверь живьём**

```bash
export MSYS_NO_PATHCONV=1
A="$ANDROID_HOME/platform-tools/adb.exe"
"$A" push C:/Users/User/point-corpus/02.jpg /data/local/tmp/c.jpg
"$A" shell "run-as com.point cp /data/local/tmp/c.jpg files/c.jpg"
"$A" shell am start -a android.intent.action.VIEW -t image/jpeg \
  -d "file:///data/user/0/com.point/files/c.jpg" -n com.point/.ShareActivity
"$A" shell uiautomator dump /sdcard/ui.xml
"$A" exec-out cat /sdcard/ui.xml | tr '>' '\n' | grep -o 'text="[^"]*"' | head -20
```
Expected: в дампе видны заголовок объекта и действия — тот же первый экран, что при шаринге.

- [ ] **Step 7: Коммит**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/kotlin/com/point/ShareActivity.kt app/src/test/kotlin/com/point/IncomingTest.kt
git commit -m "feat: Point появляется в «Открыть с помощью» для любого файла (Closes #249)"
```

---

### Task 3: Общий хост флоу для всех дверей

**Files:**
- Create: `app/src/main/kotlin/com/point/FlowHostActivity.kt`
- Modify: `app/src/main/kotlin/com/point/ShareActivity.kt` (целиком — остаётся разбор intent)
- Modify: `app/src/main/kotlin/com/point/ProcessTextActivity.kt` (целиком — остаётся разбор intent)

**Interfaces:**
- Produces: `abstract class FlowHostActivity : ComponentActivity()` с `protected val viewModel: FlowViewModel`, абстрактным `protected abstract fun accept(intent: Intent)` и открытым `protected open val restoresJourney: Boolean get() = false`.

- [ ] **Step 1: Создать базовый класс**

Создать `app/src/main/kotlin/com/point/FlowHostActivity.kt`. Блок `setContent { … PointHost(…) }` перенести **дословно** из `ShareActivity.kt:45-89` — список колбэков не переписывать, он длинный и повторяется:

```kotlin
package com.point

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.point.core.ui.theme.PointTheme

/**
 * Общий хост флоу для всех дверей Point (#249).
 *
 * Дверей стало больше одной, и каждая приносила с собой копию одного и того же экрана: список
 * из четырёх десятков колбэков `PointHost` лежал и в `ShareActivity`, и в `ProcessTextActivity`
 * — вместе с обязательной уборкой scratch и перехватом «назад». Третья дверь сделала бы три
 * копии, и разошлись бы они молча.
 *
 * Здесь живёт всё, что у дверей общее. Двери отличаются ровно одним — [accept]: как разобрать
 * свой intent и что отдать во флоу.
 */
abstract class FlowHostActivity : ComponentActivity() {

    protected val viewModel: FlowViewModel by viewModels()

    /** Разобрать свой intent и отдать объект во флоу. Вызывается на первом создании и на новом intent. */
    protected abstract fun accept(intent: Intent)

    /**
     * Поднимать ли работу, прерванную смертью процесса (#7). Только у двери «Поделиться»:
     * иконка в лаунчере обязана открывать домашний экран, а не чужую недоделанную работу.
     */
    protected open val restoresJourney: Boolean get() = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) accept(intent)
        if (restoresJourney) viewModel.restoreJourney()

        onBackPressedDispatcher.addCallback(this) {
            if (!viewModel.onBack()) {
                isEnabled = false
                this@FlowHostActivity.onBackPressedDispatcher.onBackPressed()
            }
        }

        setContent {
            PointTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val state by viewModel.ui.collectAsStateWithLifecycle()
                    // ВНИМАНИЕ исполнителю: этот вызов перенести дословно из ShareActivity.kt:47-88
                    // (все колбэки, порядок не важен, но ни один не терять).
                    PointHost(
                        state = state,
                        onBubble = viewModel::onBubble,
                        appIconFor = viewModel::appIcon,
                        // … остальные колбэки — дословно из ShareActivity
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTop: второй объект, прилетевший в живую активити, обязан начать новую работу —
        // без этого intent доставляется и молча теряется.
        accept(intent)
    }

    override fun onDestroy() {
        if (isFinishing) viewModel.endFlow() // обязательная уборка scratch
        super.onDestroy()
    }
}
```

- [ ] **Step 2: Свести ShareActivity к разбору intent**

Заменить `app/src/main/kotlin/com/point/ShareActivity.kt` целиком:

```kotlin
package com.point

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint

/**
 * Дверь «Поделиться» и «Открыть с помощью» (#249): принимает системный Share и открытие файла,
 * стрингует `content://` здесь, на границе, чтобы ниже `:app` никто не знал про Android Uri.
 */
@AndroidEntryPoint
class ShareActivity : FlowHostActivity() {

    override val restoresJourney: Boolean get() = true

    override fun accept(intent: Intent) {
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val streams = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        when (
            val incoming = incomingOf(
                action = intent.action,
                type = intent.type ?: intent.data?.let { contentResolver.getType(it) },
                data = intent.data?.toString(),
                stream = stream?.toString(),
                text = intent.getStringExtra(Intent.EXTRA_TEXT),
                streams = streams?.map { it.toString() }.orEmpty(),
            )
        ) {
            is Incoming.Single -> viewModel.onShared(incoming.uri, incoming.mime)
            is Incoming.Many -> viewModel.onSharedMultiple(incoming.uris)
            is Incoming.Body -> {
                val uri = Uri.fromFile(cacheTextFile(cacheDir, incoming.text))
                viewModel.onShared(uri.toString(), "text/plain")
            }
            null -> Unit
        }
    }
}
```

- [ ] **Step 3: Свести ProcessTextActivity к разбору intent**

Открыть `app/src/main/kotlin/com/point/ProcessTextActivity.kt`, прочитать строки 92-107 (метод `handleProcessText`) и перенести его тело в `accept`, а всё остальное — удалить, оставив класс таким:

```kotlin
package com.point

import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint

/**
 * «Правый клик по тексту»: Point зарегистрирован на ACTION_PROCESS_TEXT, поэтому появляется в
 * панели выделения любого приложения. Выделенное входит во флоу тем же путём, что объект из
 * «Поделиться» — через `onShared(fileUri, "text/plain")`.
 */
@AndroidEntryPoint
class ProcessTextActivity : FlowHostActivity() {

    override fun accept(intent: Intent) {
        // EXTRA_PROCESS_TEXT — редактируемое выделение; READONLY-вариант остаётся запасным.
        val text = (
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)
            )?.toString().orEmpty()
        if (text.isBlank()) {
            finish()
            return
        }
        val uri = Uri.fromFile(cacheTextFile(cacheDir, text))
        viewModel.onShared(uri.toString(), "text/plain")
    }
}
```

Импорты этого файла: `android.content.Intent`, `android.net.Uri`, `dagger.hilt.android.AndroidEntryPoint`.

- [ ] **Step 4: Проверить сборку и тесты**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL. Существующие тесты `:app` (`FlowViewModelTest` и соседи) остаются зелёными — они судят ViewModel, а не активити.

- [ ] **Step 5: Проверить обе двери живьём**

```bash
export MSYS_NO_PATHCONV=1
A="$ANDROID_HOME/platform-tools/adb.exe"
"$A" install -r -t app/build/outputs/apk/debug/app-debug.apk
"$A" shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "проверка двери" -n com.point/.ShareActivity
"$A" shell uiautomator dump /sdcard/ui.xml && "$A" exec-out cat /sdcard/ui.xml | grep -c "Понять"
```
Expected: первый экран Point с действиями (счётчик > 0).

- [ ] **Step 6: Коммит**

```bash
git add app/src/main/kotlin/com/point/FlowHostActivity.kt app/src/main/kotlin/com/point/ShareActivity.kt app/src/main/kotlin/com/point/ProcessTextActivity.kt
git commit -m "refactor: у дверей один хост флоу — экран перестал копироваться в каждую (part of #249)"
```

---

### Task 4: Доступ к кадру выделения — за контракт

**Files:**
- Create: `app/src/main/kotlin/com/point/SelectionFrames.kt`
- Modify: `app/src/main/kotlin/com/point/FlowViewModel.kt:47` (импорт), `:545`, `:632`, `:676` (вызовы)
- Modify: `app/src/main/kotlin/com/point/di/` — модуль Hilt (найти существующий модуль `:app`; если его нет, создать `app/src/main/kotlin/com/point/di/AppBindings.kt`)
- Test: `app/src/test/kotlin/com/point/FlowViewModelTest.kt` (дописывается в задаче 5)

**Interfaces:**
- Produces: `interface SelectionFrames { fun frame(path: String, maxPx: Int): SelectionFrame?; fun crop(path: String, left: Int, top: Int, right: Int, bottom: Int): Bitmap? }`; реализация `AndroidSelectionFrames`, инжектируемая в `FlowViewModel` как `private val frames: SelectionFrames`.

- [ ] **Step 1: Создать контракт и реализацию**

Создать `app/src/main/kotlin/com/point/SelectionFrames.kt`:

```kotlin
package com.point

import android.graphics.Bitmap
import com.point.data.SelectionFrame
import com.point.data.cropRegion
import com.point.data.decodeSelectionFrame
import javax.inject.Inject

/**
 * Картинка под выделением — за контрактом (инвариант «каждый side-effect за интерфейсом»).
 *
 * Прямой вызов декодера из ViewModel делал выделение непроверяемым на JVM: любой тест утыкался
 * в `android.graphics`. Через контракт тест подставляет фейк и судит РЕШЕНИЯ выделения, а не
 * умение Android декодировать JPEG.
 */
interface SelectionFrames {
    /** Кадр страницы под выделение; `null` — картинку прочитать не удалось. */
    fun frame(path: String, maxPx: Int): SelectionFrame?

    /** Вырезать обведённое; `null` — вырезать не удалось. */
    fun crop(path: String, left: Int, top: Int, right: Int, bottom: Int): Bitmap?
}

class AndroidSelectionFrames @Inject constructor() : SelectionFrames {
    override fun frame(path: String, maxPx: Int) = decodeSelectionFrame(path, maxPx)
    override fun crop(path: String, left: Int, top: Int, right: Int, bottom: Int) =
        cropRegion(path, left, top, right, bottom)
}
```

- [ ] **Step 2: Связать в Hilt**

Модуль `:app` уже есть — `app/src/main/kotlin/com/point/AppIcons.kt:44-49` (`AppIconsModule`, `abstract class` с `@Binds`). Дописать в него связку рядом с существующей:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class AppIconsModule {
    @Binds
    abstract fun appIconResolver(impl: AppIcons): AppIconResolver

    @Binds
    abstract fun selectionFrames(impl: AndroidSelectionFrames): SelectionFrames
}
```

- [ ] **Step 3: Провести контракт в ViewModel**

В `app/src/main/kotlin/com/point/FlowViewModel.kt` добавить параметр конструктора `private val frames: SelectionFrames,`, удалить импорт `com.point.data.decodeSelectionFrame` (строка 47) и заменить три вызова:

- строка ~545 и ~676: `decodeSelectionFrame(top.uri.value, SELECTION_MAX_PX)` → `frames.frame(top.uri.value, SELECTION_MAX_PX)`
- строка ~632: `com.point.data.cropRegion(top.uri.value, …)` → `frames.crop(top.uri.value, …)`

- [ ] **Step 4: Починить существующие тесты**

`FlowViewModelTest` создаёт `FlowViewModel` напрямую — добавить в конструктор фейк. Дописать в тестовый файл:

```kotlin
    /** Кадра нет: JVM не умеет android.graphics, и это честно — тест судит решения, а не декодер. */
    private val noFrames = object : SelectionFrames {
        override fun frame(path: String, maxPx: Int) = null
        override fun crop(path: String, left: Int, top: Int, right: Int, bottom: Int) = null
    }
```

и передать `frames = noFrames` во все места создания `FlowViewModel` в тесте.

- [ ] **Step 5: Проверить**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Коммит**

```bash
git add app/src/main/kotlin/com/point/SelectionFrames.kt app/src/main/kotlin/com/point/FlowViewModel.kt app/src/test/kotlin/com/point/FlowViewModelTest.kt
git commit -m "refactor: кадр выделения приходит за контрактом — решения выделения стали проверяемы (part of #259)"
```

---

### Task 5: Выделение работает без распознавания

**Files:**
- Create: `app/src/main/kotlin/com/point/HeroTap.kt`
- Test: `app/src/test/kotlin/com/point/HeroTapTest.kt`
- Modify: `app/src/main/kotlin/com/point/PointHost.kt:281-285` (выбор действия по тапу)
- Modify: `app/src/main/kotlin/com/point/FlowViewModel.kt:538-560` (метод `openSelection`)
- Test: `app/src/test/kotlin/com/point/FlowViewModelTest.kt` (дописывается)

**Interfaces:**
- Consumes: `SelectionFrames` из задачи 4.
- Produces: `enum class HeroTap { SELECT, OPEN }`; функция `heroTapOf(kind: ObjectKind, hasWordLayer: Boolean): HeroTap`.

- [ ] **Step 1: Написать падающий тест на правило тапа**

Создать `app/src/test/kotlin/com/point/HeroTapTest.kt`:

```kotlin
package com.point

import com.point.core.model.ObjectKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Что делает тап по самому крупному элементу экрана.
 *
 * Прежнее правило требовало слой слов: обвести картинку можно было только после чтения. Владелец
 * возразил по существу — выделение для того и нужно, чтобы указать область, и распознавание тут
 * не условие, а одно из возможных продолжений.
 */
class HeroTapTest {

    @Test
    fun `картинку можно обвести и без чтения`() {
        assertEquals(HeroTap.SELECT, heroTapOf(ObjectKind.IMAGE, hasWordLayer = false))
    }

    @Test
    fun `прочитанную картинку тоже обводят — рамка липнет к словам`() {
        assertEquals(HeroTap.SELECT, heroTapOf(ObjectKind.IMAGE, hasWordLayer = true))
    }

    @Test
    fun `у текста обводить нечего — тап открывает объект`() {
        assertEquals(HeroTap.OPEN, heroTapOf(ObjectKind.TEXT, hasWordLayer = true))
    }

    @Test
    fun `архив тап открывает, а не обводит`() {
        assertEquals(HeroTap.OPEN, heroTapOf(ObjectKind.ZIP, hasWordLayer = false))
    }
}
```

- [ ] **Step 2: Убедиться, что падает**

Run: `./gradlew :app:testDebugUnitTest --tests "com.point.HeroTapTest"`
Expected: FAIL — `Unresolved reference: heroTapOf`.

- [ ] **Step 3: Реализовать правило**

Создать `app/src/main/kotlin/com/point/HeroTap.kt`:

```kotlin
package com.point

import com.point.core.model.ObjectKind

/** Что делает тап по объекту на первом экране (#290, #259). */
enum class HeroTap { SELECT, OPEN }

/**
 * Обводить можно то, у чего есть пиксели: картинку. Слой слов больше не условие, а лишь разница
 * в поведении рамки — есть слой, рамка липнет к словам и выделение даёт текст; нет слоя, рамка
 * свободная и выделение даёт кадр (`fragmentCapture`).
 */
fun heroTapOf(kind: ObjectKind, hasWordLayer: Boolean): HeroTap =
    if (kind == ObjectKind.IMAGE) HeroTap.SELECT else HeroTap.OPEN
```

Параметр `hasWordLayer` намеренно не используется в теле: он остаётся в сигнатуре, потому что правило про него — часть договора, и тест закрепляет, что наличие слоя ничего не меняет. Если линтер ругается на неиспользуемый параметр, пометить его `@Suppress("UNUSED_PARAMETER")` с комментарием.

- [ ] **Step 4: Убедиться, что проходит**

Run: `./gradlew :app:testDebugUnitTest --tests "com.point.HeroTapTest"`
Expected: PASS, 4 теста.

- [ ] **Step 5: Применить правило в UI**

В `app/src/main/kotlin/com/point/PointHost.kt` заменить блок `onHeroTap = if (current.obj.metadata.containsKey(…)) { onOpenSelection } else { onOpenObject },` на:

```kotlin
                    // Тап по объекту всегда что-то делает (#290). Картинку — обводим, независимо
                    // от того, читали её или нет (#259): выделение и есть способ указать область.
                    onHeroTap = when (
                        heroTapOf(
                            current.obj.state.kind,
                            hasWordLayer = current.obj.metadata.containsKey(com.point.core.flow.META_OCR_ATOMS_REF),
                        )
                    ) {
                        HeroTap.SELECT -> onOpenSelection
                        HeroTap.OPEN -> onOpenObject
                    },
```

- [ ] **Step 6: Написать падающий тест на openSelection без слоя**

Дописать в `app/src/test/kotlin/com/point/FlowViewModelTest.kt`:

```kotlin
    @Test
    fun `выделение открывается и без слоя слов — отказ приходит от картинки, а не от чтения`() = runTest {
        // Объект-картинка без META_OCR_ATOMS_REF: раньше openSelection выходил молча.
        // Теперь он доходит до картинки; фейк кадра возвращает null, и человек слышит причину.
        val vm = newViewModel()               // создаётся с frames = noFrames
        vm.onShared("/tmp/shot.jpg", "image/jpeg")
        advanceUntilIdle()

        vm.openSelection()
        advanceUntilIdle()

        assertEquals("Не удалось открыть страницу для выделения", vm.ui.value.message)
        assertEquals(Outcome.FAILED, vm.ui.value.messageOutcome)
    }
```

Имя `newViewModel()` — фабрика, уже используемая в этом файле; если она называется иначе, вызвать существующую.

- [ ] **Step 7: Убедиться, что падает**

Run: `./gradlew :app:testDebugUnitTest --tests "com.point.FlowViewModelTest"`
Expected: FAIL — сообщения нет, `openSelection` выходит на проверке слоя.

- [ ] **Step 8: Снять требование слоя**

В `app/src/main/kotlin/com/point/FlowViewModel.kt` в методе `openSelection` заменить начало:

```kotlin
    fun openSelection() {
        val top = stack.lastOrNull()?.obj ?: return
        // Слой слов необязателен (#259): есть — рамка липнет к словам и выделение даёт текст;
        // нет — рамка свободная и выделение даёт кадр. Требовать чтение до обводки значит
        // заставлять человека распознавать то, что он всего лишь хочет обвести.
        val atomsRef = top.metadata[META_OCR_ATOMS_REF]
        viewModelScope.launch {
            val loaded = withContext(ioDispatcher) {
                runCatching {
                    val layer = atomsRef?.let { AtomCodec.decode(File(it).readText()) }
                    frames.frame(top.uri.value, SELECTION_MAX_PX)?.let { frame ->
                        Triple(layer, frame.transform, frame.bitmap.asImageBitmap())
                    }
                }.getOrNull()
            }
            if (loaded == null) {
                _ui.update { it.copy(message = "Не удалось открыть страницу для выделения", messageOutcome = Outcome.FAILED) }
                return@launch
            }
            selectionLayer = loaded.first
            selectionTransform = loaded.second
            selectionSnap = null
            _ui.update { it.copy(selection = SelectionUi(image = loaded.third)) }
        }
    }
```

Тип поля `selectionLayer` сделать nullable (`AtomLayer?`), если он ещё не такой, и проверить места его чтения: там, где раньше слой был гарантирован, теперь пустое выделение означает «текста нет» — то есть `takeSelection` уйдёт в `fragmentCapture`, что и требуется.

- [ ] **Step 9: Убедиться, что проходит**

Run: `./gradlew :app:testDebugUnitTest --tests "com.point.FlowViewModelTest"`
Expected: PASS.

- [ ] **Step 10: Полная проверка и живой прогон**

```bash
./gradlew test assembleDebug
"$ANDROID_HOME/platform-tools/adb.exe" install -r -t app/build/outputs/apk/debug/app-debug.apk
```
Затем на устройстве: расшарить фото, **не** распознавая текст, тапнуть по объекту — должен открыться экран выделения; обвести кусок, нажать «взять» — в работе появляется новый объект-картинка «Выделение».

Положительный путь проверяется руками намеренно: он упирается в `android.graphics`, а Robolectric в проекте нет.

- [ ] **Step 11: Коммит**

```bash
git add app/src/main/kotlin/com/point/HeroTap.kt app/src/test/kotlin/com/point/HeroTapTest.kt app/src/main/kotlin/com/point/PointHost.kt app/src/main/kotlin/com/point/FlowViewModel.kt app/src/test/kotlin/com/point/FlowViewModelTest.kt
git commit -m "feat: область обводится без распознавания — чтение стало продолжением, а не условием (Closes #259)"
```

---

## Что этот план не делает

Остальные четыре среза спеки — шторка, экран, принтер, локация — планируются отдельно, когда эти два влиты. Так договорено в спеке: шесть дверей — шесть issue и шесть PR.

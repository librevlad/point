# Шторка: плитка, экран выбора и три источника — план работ

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Из шторки в два тапа рождается объект — снимок камеры, содержимое буфера или голосовая запись.

**Architecture:** Плитка «Point» открывает прозрачный экран выбора источника. Источник — контракт `ObjectSource` с реализациями в `:app`, зарегистрированными через Hilt `@IntoSet`; добавить источник = добавить класс. Добытое уходит в `ShareActivity` внутренним `ACTION_SEND` — шторка никогда не выполняет Capability и не обрабатывает объект (#246). Съёмку и запись делают системные приложения, поэтому ни одного нового разрешения этот срез не добавляет.

**Tech Stack:** Kotlin, Compose, Hilt, JUnit4 (`:app` тестируется на JVM, без Robolectric).

## Global Constraints

- Ни одного нового `uses-permission`: камера — `ACTION_IMAGE_CAPTURE`, голос — `RECORD_SOUND_ACTION`, буфер — чтение на переднем плане.
- Шторка только создаёт объект. Ни OCR, ни AI, ни экспорта в ней нет.
- `:core:model` и `:core:flow` остаются Android-free.
- Каждый side-effect за интерфейсом; в тестах — fakes.
- Backtick-имена тестов без `:` и `;` — тире вместо них.
- Один класс тестов: `./gradlew :app:testDebugUnitTest --tests "com.point.ИМЯ"`.
- Перед сборкой: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`; полная проверка — `./gradlew test assembleDebug`.
- Отказ говорит словами: отмена съёмки — молча назад, пустой буфер — «в буфере пусто».
- FileProvider уже есть (`data/src/main/AndroidManifest.xml`, authority `${applicationId}.fileprovider`, пути — `filesDir/scratch`). Камера пишет в scratch-файл из `ObjectStore.newScratchFile("jpg")`.

---

### Task 1: Контракт источника и чистые превращения

**Files:**
- Create: `app/src/main/kotlin/com/point/source/ObjectSource.kt`
- Create: `app/src/main/kotlin/com/point/source/Produced.kt`
- Test: `app/src/test/kotlin/com/point/source/ProducedTest.kt`

**Interfaces:**
- Produces: `data class Produced(val uri: String, val mime: String)`; `interface ObjectSource { val id: String; val label: String; fun isAvailable(context: Context): Boolean; suspend fun request(context: Context): Intent?; suspend fun read(context: Context, data: Intent?): Produced? }`; чистые функции `clipToProduced(text: String?, uri: String?, mime: String?, textFile: (String) -> String): Produced?` и `captureToProduced(path: String, sizeBytes: Long): Produced?`.

- [ ] **Step 1: Написать падающий тест**

Создать `app/src/test/kotlin/com/point/source/ProducedTest.kt`:

```kotlin
package com.point.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Превращение добытого в объект — чистые функции: что именно родится из буфера и из снимка,
 * решается здесь и судится на JVM. Источникам остаётся системная работа.
 */
class ProducedTest {

    @Test
    fun `текст из буфера ложится в файл и становится текстовым объектом`() {
        val produced = clipToProduced(
            text = "накладная 204514", uri = null, mime = null,
            textFile = { "file:///scratch/clip.txt" },
        )
        assertEquals(Produced("file:///scratch/clip.txt", "text/plain"), produced)
    }

    @Test
    fun `файл из буфера идёт своей ссылкой и своим типом`() {
        val produced = clipToProduced(
            text = null, uri = "content://media/42", mime = "image/png",
            textFile = { error("файл не пишем") },
        )
        assertEquals(Produced("content://media/42", "image/png"), produced)
    }

    @Test
    fun `у файла без типа — общий тип, а не выдуманный`() {
        val produced = clipToProduced(
            text = null, uri = "content://media/42", mime = null,
            textFile = { error("файл не пишем") },
        )
        assertEquals(Produced("content://media/42", "application/octet-stream"), produced)
    }

    @Test
    fun `пустой буфер — ничего, а не пустой объект`() {
        assertNull(clipToProduced(text = "   ", uri = null, mime = null, textFile = { "нет" }))
        assertNull(clipToProduced(text = null, uri = null, mime = null, textFile = { "нет" }))
    }

    @Test
    fun `снятый кадр становится объектом-картинкой`() {
        val produced = captureToProduced("/scratch/shot.jpg", sizeBytes = 240_000)
        assertEquals(Produced("/scratch/shot.jpg", "image/jpeg"), produced)
    }

    @Test
    fun `отменённая съёмка оставляет пустой файл — объекта нет`() {
        // Камера создаёт файл заранее; отмена оставляет его нулевым, и это НЕ объект.
        assertNull(captureToProduced("/scratch/shot.jpg", sizeBytes = 0))
    }
}
```

- [ ] **Step 2: Убедиться, что падает**

Run: `./gradlew :app:testDebugUnitTest --tests "com.point.source.ProducedTest"`
Expected: FAIL — `Unresolved reference: clipToProduced`.

- [ ] **Step 3: Реализовать**

Создать `app/src/main/kotlin/com/point/source/Produced.kt`:

```kotlin
package com.point.source

/** Что добыл источник: ссылка и тип — ровно то, что умеет принять `FlowViewModel.onShared`. */
data class Produced(val uri: String, val mime: String)

/** Тип, когда система его не назвала. */
private const val UNKNOWN_MIME = "application/octet-stream"

/**
 * Что родится из буфера обмена.
 *
 * Файл побеждает текст: если в буфере лежит ссылка на файл, объектом становится он сам, а не его
 * текстовое представление. Пустота — не объект: пустой объект в работе хуже честного «в буфере
 * пусто».
 */
fun clipToProduced(
    text: String?,
    uri: String?,
    mime: String?,
    textFile: (String) -> String,
): Produced? = when {
    uri != null -> Produced(uri, mime ?: UNKNOWN_MIME)
    !text.isNullOrBlank() -> Produced(textFile(text), "text/plain")
    else -> null
}

/**
 * Что родится из камеры.
 *
 * Файл создаётся ДО съёмки (камере нужно, куда писать), поэтому его существование ничего не
 * доказывает — доказывает размер. Отменённая съёмка оставляет нулевой файл, и объектом он не
 * становится: иначе человек получил бы пустую карточку вместо честной тишины.
 */
fun captureToProduced(path: String, sizeBytes: Long): Produced? =
    if (sizeBytes > 0) Produced(path, "image/jpeg") else null
```

Создать `app/src/main/kotlin/com/point/source/ObjectSource.kt`:

```kotlin
package com.point.source

import android.content.Context
import android.content.Intent

/**
 * Источник объекта — дверь из шторки (#246).
 *
 * Живёт в `:app`, а не в core: источники по природе завязаны на Android (камера, буфер, диктофон),
 * и тащить это в Android-free модули нельзя. Регистрируется через Hilt `@IntoSet`, как Capability:
 * добавить источник = добавить класс, экран выбора вырастает сам.
 *
 * Шторка только создаёт объект и никогда его не обрабатывает — это требование #246 дословно.
 */
interface ObjectSource {
    /** Устойчивый идентификатор: по нему экран выбора помнит, кого запускали. */
    val id: String

    /** Как источник называется человеку. */
    val label: String

    /** Есть ли чем добывать: нет системной камеры — плашки не показываем вовсе. */
    fun isAvailable(context: Context): Boolean

    /** Чужая активити, если нужна; `null` — источник добывает сам (буфер).
     *  `suspend`, потому что заготовка файла — работа с диском: камере нужно, куда писать. */
    suspend fun request(context: Context): Intent?

    /** Что получилось. [data] — результат чужой активити, `null` для источников без неё. */
    suspend fun read(context: Context, data: Intent?): Produced?
}
```

- [ ] **Step 4: Убедиться, что проходит**

Run: `./gradlew :app:testDebugUnitTest --tests "com.point.source.ProducedTest"`
Expected: PASS, 6 тестов.

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/kotlin/com/point/source app/src/test/kotlin/com/point/source
git commit -m "feat: контракт источника объекта и чистые превращения добытого (part of #246)"
```

---

### Task 2: Плитка и экран выбора

**Files:**
- Create: `app/src/main/kotlin/com/point/source/PointTileService.kt`
- Create: `app/src/main/kotlin/com/point/source/SourcePickerActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml` (регистрация активити и сервиса)

**Interfaces:**
- Consumes: `ObjectSource`, `Produced` из задачи 1.
- Produces: `SourcePickerActivity`, которая запускает `ShareActivity` внутренним `ACTION_SEND` с добытым `Uri`.

- [ ] **Step 1: Экран выбора**

Создать `app/src/main/kotlin/com/point/source/SourcePickerActivity.kt`:

```kotlin
package com.point.source

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.point.ShareActivity
import com.point.core.ui.theme.PointTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * «Что превратить в объект?» — экран выбора источника из шторки (#246).
 *
 * Живёт поверх чужого приложения и исчезает, как только объект родился. Здесь же решается вопрос,
 * из-за которого этот экран вообще нужен активити, а не сервисом: буфер обмена Android отдаёт
 * только приложению на переднем плане (тот же приём, что у `ClipboardSyncActivity`).
 */
@AndroidEntryPoint
class SourcePickerActivity : ComponentActivity() {

    @Inject lateinit var sources: Set<@JvmSuppressWildcards ObjectSource>

    private var pending: ObjectSource? = null

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val source = pending ?: return@registerForActivityResult finish()
        pending = null
        lifecycleScope.launch {
            deliver(source.read(this@SourcePickerActivity, result.data))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val visible = sources.filter { it.isAvailable(this) }.sortedBy { it.label }
        setContent {
            PointTheme {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Что превратить в объект?")
                    visible.forEach { source ->
                        Button(onClick = { start(source) }) { Text(source.label) }
                    }
                }
            }
        }
    }

    private fun start(source: ObjectSource) = lifecycleScope.launch {
        val request = source.request(this@SourcePickerActivity)
        if (request == null) {
            deliver(source.read(this@SourcePickerActivity, null))
            return@launch
        }
        pending = source
        launcher.launch(request)
    }

    /** Объект уходит в обычную дверь: шторка его не обрабатывает (#246). */
    private fun deliver(produced: Produced?) {
        if (produced == null) return finish() // отказ уже назван источником
        startActivity(
            Intent(this, ShareActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType(produced.mime)
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(produced.uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        finish()
    }
}
```

- [ ] **Step 2: Плитка**

Создать `app/src/main/kotlin/com/point/source/PointTileService.kt`:

```kotlin
package com.point.source

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Плитка «Point» в шторке (#246): один тап открывает экран выбора источника.
 *
 * Отдельная от плитки «Общий буфер» (#161): та синхронизирует буфер с ПК и объекта не рождает.
 */
class PointTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, SourcePickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
```

- [ ] **Step 3: Манифест**

В `app/src/main/AndroidManifest.xml` перед закрывающим `</application>` добавить:

```xml
        <!-- Шторка рождает объект (#246): плитка открывает выбор источника, экран живёт поверх
             чужого приложения и исчезает, как только объект родился. -->
        <activity
            android:name=".source.SourcePickerActivity"
            android:exported="false"
            android:theme="@android:style/Theme.Translucent.NoTitleBar"
            android:excludeFromRecents="true"
            android:launchMode="singleTop" />

        <service
            android:name=".source.PointTileService"
            android:exported="true"
            android:icon="@android:drawable/ic_menu_add"
            android:label="Point"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>
        </service>
```

- [ ] **Step 4: Проверить сборку**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL. Экран пока пуст — источников нет ни одного, это ожидаемо.

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/kotlin/com/point/source app/src/main/AndroidManifest.xml
git commit -m "feat: плитка Point и экран выбора источника (part of #246)"
```

---

### Task 3: Источник «Буфер»

**Files:**
- Create: `app/src/main/kotlin/com/point/source/ClipboardSource.kt`
- Modify: `app/src/main/kotlin/com/point/AppIcons.kt` (модуль Hilt — регистрация источника)
- Test: `app/src/test/kotlin/com/point/source/ProducedTest.kt` (покрыт задачей 1)

**Interfaces:**
- Consumes: `clipToProduced`, `ObjectSource`, `Produced`.
- Produces: `ClipboardSource` в наборе `Set<ObjectSource>`.

- [ ] **Step 1: Реализовать источник**

Создать `app/src/main/kotlin/com/point/source/ClipboardSource.kt`:

```kotlin
package com.point.source

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import javax.inject.Inject

/**
 * Буфер обмена как источник объекта (#246).
 *
 * Чужой активити не нужно: буфер читается прямо здесь, потому что экран выбора уже на переднем
 * плане, — единственное состояние, в котором Android отдаёт содержимое буфера.
 */
class ClipboardSource @Inject constructor() : ObjectSource {

    override val id = "clipboard"
    override val label = "Буфер обмена"

    override fun isAvailable(context: Context) =
        context.getSystemService(Context.CLIPBOARD_SERVICE) is ClipboardManager

    override suspend fun request(context: Context): Intent? = null

    override suspend fun read(context: Context, data: Intent?): Produced? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val item = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val uri = item?.uri
        val produced = clipToProduced(
            text = item?.text?.toString(),
            uri = uri?.toString(),
            mime = uri?.let { context.contentResolver.getType(it) },
            // `cacheTextFile` — internal в пакете `com.point`, отсюда он не виден; текст
            // кладётся тем же способом, но своей строкой — дублировать один вызов дешевле, чем
            // расширять видимость ради него.
            textFile = { text ->
                val file = java.io.File.createTempFile("clip-", ".txt", context.cacheDir)
                file.writeText(text)
                android.net.Uri.fromFile(file).toString()
            },
        )
        // Пустота названа словами: молчание в ответ на тап — та же ложь, что заглушка вместо
        // статуса (#358).
        if (produced == null) Toast.makeText(context, "В буфере пусто", Toast.LENGTH_SHORT).show()
        return produced
    }
}
```

- [ ] **Step 2: Зарегистрировать в Hilt**

В `app/src/main/kotlin/com/point/AppIcons.kt`, в `AppIconsModule`, добавить:

```kotlin
    @Binds
    @IntoSet
    abstract fun clipboardSource(impl: com.point.source.ClipboardSource): com.point.source.ObjectSource
```

Импорт `dagger.multibindings.IntoSet` добавить в шапку файла.

- [ ] **Step 3: Проверить сборку**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Проверить живьём**

```bash
export MSYS_NO_PATHCONV=1
A="$ANDROID_HOME/platform-tools/adb.exe"
"$A" install -r -t app/build/outputs/apk/debug/app-debug.apk
"$A" shell am start -n com.point/.source.SourcePickerActivity
"$A" shell uiautomator dump /sdcard/ui.xml
"$A" exec-out cat /sdcard/ui.xml | tr '>' '\n' | grep -o 'text="[^"]*"' | head
```
Expected: «Что превратить в объект?» и плашка «Буфер обмена».

Тап по плашке проверяется вручную: на эмуляторе буфер пуст, поэтому ожидаемый ответ — «В буфере пусто». Наполнить буфер и проверить рождение объекта может только владелец на своём телефоне (на A34 `cmd clipboard` заблокирован).

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/kotlin/com/point/source/ClipboardSource.kt app/src/main/kotlin/com/point/AppIcons.kt
git commit -m "feat: буфер обмена как источник объекта из шторки (part of #246)"
```

---

### Task 4: Источник «Камера»

**Files:**
- Create: `app/src/main/kotlin/com/point/source/CameraSource.kt`
- Modify: `app/src/main/kotlin/com/point/AppIcons.kt` (регистрация)

**Interfaces:**
- Consumes: `captureToProduced`, `ObjectSource`, `ObjectStore` (для `newScratchFile`).
- Produces: `CameraSource` в наборе `Set<ObjectSource>`.

- [ ] **Step 1: Реализовать источник**

Создать `app/src/main/kotlin/com/point/source/CameraSource.kt`:

```kotlin
package com.point.source

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.point.core.flow.ObjectStore
import java.io.File
import javax.inject.Inject

/**
 * Камера как источник объекта (#246) — чужими руками.
 *
 * Снимает системное приложение камеры, поэтому разрешение `CAMERA` Point не просит вовсе: у него
 * в манифесте нет ни одного `uses-permission`, и терять это свойство ради двух сэкономленных
 * тапов не стоит.
 *
 * Кадр пишется сразу в scratch — туда же, куда попадают все объекты Point, и оттуда его умеет
 * раздать FileProvider (`filesDir/scratch`).
 */
class CameraSource @Inject constructor(
    private val store: ObjectStore,
) : ObjectSource {

    override val id = "camera"
    override val label = "Камера"

    private var target: File? = null

    override fun isAvailable(context: Context): Boolean =
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) != null

    override suspend fun request(context: Context): Intent {
        val file = File(store.newScratchFile("jpg").value)
        target = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    override suspend fun read(context: Context, data: Intent?): Produced? {
        val file = target ?: return null
        target = null
        // Отмена съёмки оставляет заготовленный файл нулевым — объектом он не становится, и
        // человеку об этом говорить нечего: он сам только что нажал «отмена».
        return captureToProduced(android.net.Uri.fromFile(file).toString(), file.length())
    }
}
```

- [ ] **Step 2: Зарегистрировать в Hilt**

В `AppIconsModule` добавить рядом с предыдущей:

```kotlin
    @Binds
    @IntoSet
    abstract fun cameraSource(impl: com.point.source.CameraSource): com.point.source.ObjectSource
```

- [ ] **Step 3: Проверить сборку**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Проверить живьём**

```bash
"$A" install -r -t app/build/outputs/apk/debug/app-debug.apk
"$A" shell am start -n com.point/.source.SourcePickerActivity
"$A" shell uiautomator dump /sdcard/ui.xml
"$A" exec-out cat /sdcard/ui.xml | tr '>' '\n' | grep -o 'text="[^"]*"' | head
```
Expected: в списке появилась плашка «Камера». На эмуляторе камера есть (виртуальная сцена), поэтому тап открывает системную камеру; снимок доводится вручную.

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/kotlin/com/point/source/CameraSource.kt app/src/main/kotlin/com/point/AppIcons.kt
git commit -m "feat: камера как источник объекта из шторки — чужими руками, без разрешений (part of #246)"
```

---

### Task 5: Источник «Голос»

**Files:**
- Create: `app/src/main/kotlin/com/point/source/VoiceSource.kt`
- Modify: `app/src/main/kotlin/com/point/AppIcons.kt` (регистрация)

**Interfaces:**
- Consumes: `ObjectSource`, `Produced`.
- Produces: `VoiceSource` в наборе `Set<ObjectSource>`.

- [ ] **Step 1: Реализовать источник**

Создать `app/src/main/kotlin/com/point/source/VoiceSource.kt`:

```kotlin
package com.point.source

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import javax.inject.Inject

/**
 * Голос как источник объекта (#246) — системным диктофоном.
 *
 * Записывает чужое приложение, поэтому `RECORD_AUDIO` Point не просит. Что записанное умеет
 * Point: сохранить и переслать. «Понять» голос пока нельзя — разбор `ogg` открыт в #223, и
 * обещать больше, чем есть, здесь нечего.
 */
class VoiceSource @Inject constructor() : ObjectSource {

    override val id = "voice"
    override val label = "Голос"

    override fun isAvailable(context: Context): Boolean =
        Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION).resolveActivity(context.packageManager) != null

    override suspend fun request(context: Context): Intent =
        Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)

    override suspend fun read(context: Context, data: Intent?): Produced? {
        val uri = data?.data ?: return null // человек вышел из диктофона, не записав
        val mime = context.contentResolver.getType(uri) ?: "audio/*"
        return Produced(uri.toString(), mime)
    }
}
```

- [ ] **Step 2: Зарегистрировать в Hilt**

В `AppIconsModule` добавить:

```kotlin
    @Binds
    @IntoSet
    abstract fun voiceSource(impl: com.point.source.VoiceSource): com.point.source.ObjectSource
```

- [ ] **Step 3: Проверить сборку и экран**

Run: `./gradlew test assembleDebug`, затем установить и открыть `SourcePickerActivity`.
Expected: BUILD SUCCESSFUL; на эмуляторе без диктофона плашки «Голос» не будет — и это правильное поведение (`isAvailable` = false), а не поломка.

- [ ] **Step 4: Коммит**

```bash
git add app/src/main/kotlin/com/point/source/VoiceSource.kt app/src/main/kotlin/com/point/AppIcons.kt
git commit -m "feat: голос как источник объекта из шторки — системным диктофоном (part of #246)"
```

---

## Что этот план не делает

- **Экран** как источник — отдельный срез: `MediaProjection`, служебные разрешения в манифесте и самоперекрытие экрана выбора требуют своей работы.
- **Локация** — последней: единственный источник, просящий настоящее разрешение.
- **Красота экрана выбора.** Здесь он собран из штатных кнопок Compose; портал и плашки в стилистике дизайн-системы — отдельная правка после того, как поток заработает.
- **Кнопка «добавить плитку»** (`requestAddTileService`, API 33+) — плитку пока человек добавляет в шторку сам.

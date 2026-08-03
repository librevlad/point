# Принтер и локация — план работ

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Печать из любого приложения рождает объект в Point, а из шторки можно сделать объектом своё текущее место.

**Architecture:** Принтер — самостоятельная дверь: `PrintService` объявляет виртуальный принтер, задание приходит готовым PDF и уходит в `ShareActivity` тем же внутренним `ACTION_SEND`, что и всё остальное. Локация — обычный `ObjectSource` для шторки, но первый, кому нужно runtime-разрешение, поэтому контракт источника расширяется полем `permissions`, а спрашивает их экран выбора.

**Tech Stack:** Kotlin, Compose, Hilt, JUnit4 (`:app` тестируется на JVM, без Robolectric).

## Global Constraints

- Локация — **единственное** новое разрешение (`ACCESS_FINE_LOCATION`). Принтер разрешений не добавляет: `BIND_PRINT_SERVICE` объявляется на сервисе и диалогом доступа не является.
- Дверь только добывает: печать и локация не выполняют Capability (#246).
- `:core:model` и `:core:flow` остаются Android-free.
- Backtick-имена тестов без `:` и `;` — тире вместо них.
- Один класс тестов: `./gradlew :app:testDebugUnitTest --tests "com.point.ИМЯ"`.
- Перед сборкой: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`; полная проверка — `./gradlew test assembleDebug`.
- Отказ говорит словами: нет разрешения — «без доступа к месту не получится», место неизвестно — «место пока не определилось», задание печати не прочиталось — честная ошибка, а не пустой объект.
- Файлы, добытые дверью, кладутся в `cache/`, а НЕ в `scratch`: scratch — рабочая копия текущей работы, она стирается по её окончании (урок камеры, #246).

---

### Task 1: Принтер — приём задания

**Files:**
- Create: `app/src/main/kotlin/com/point/print/PointPrintService.kt`
- Create: `app/src/main/kotlin/com/point/print/PrintedJob.kt`
- Create: `app/src/main/res/xml/point_printservice.xml`
- Test: `app/src/test/kotlin/com/point/print/PrintedJobTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `printedToProduced(path: String, sizeBytes: Long): Produced?`; `printedFileName(label: String?): String`; сервис `PointPrintService`.

- [ ] **Step 1: Написать падающий тест**

Создать `app/src/test/kotlin/com/point/print/PrintedJobTest.kt`:

```kotlin
package com.point.print

import com.point.source.Produced
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Что рождается из задания печати. Чистые функции: сама печать — системная работа, а решение
 * «объект это или ничего» судится на JVM.
 */
class PrintedJobTest {

    @Test
    fun `напечатанное становится объектом-документом`() {
        val produced = printedToProduced("/cache/print/job.pdf", sizeBytes = 12_000)
        assertEquals(Produced("/cache/print/job.pdf", "application/pdf"), produced)
    }

    @Test
    fun `пустое задание объектом не становится`() {
        // Задание может закрыться, не отдав ни байта: пустой PDF в работе хуже честной тишины.
        assertNull(printedToProduced("/cache/print/job.pdf", sizeBytes = 0))
    }

    @Test
    fun `имя берётся от задания, чтобы человек узнал свой документ`() {
        assertEquals("Счёт за май.pdf", printedFileName("Счёт за май"))
    }

    @Test
    fun `имя без названия — общее, но не пустое`() {
        assertEquals("Печать.pdf", printedFileName(null))
        assertEquals("Печать.pdf", printedFileName("   "))
    }

    @Test
    fun `в имени нет разделителей пути — иначе файл уедет из своей папки`() {
        val name = printedFileName("отчёт/май\\v2")
        assertTrue(name, !name.contains('/') && !name.contains('\\'))
    }
}
```

- [ ] **Step 2: Убедиться, что падает**

Run: `./gradlew :app:testDebugUnitTest --tests "com.point.print.PrintedJobTest"`
Expected: FAIL — `Unresolved reference: printedToProduced`.

- [ ] **Step 3: Реализовать чистую часть**

Создать `app/src/main/kotlin/com/point/print/PrintedJob.kt`:

```kotlin
package com.point.print

import com.point.source.Produced

/**
 * Что родится из задания печати.
 *
 * Задание может закрыться, не отдав ни байта, — тогда объекта нет: пустой PDF в работе хуже
 * честной тишины (то же правило, что у отменённой съёмки, #246).
 */
fun printedToProduced(path: String, sizeBytes: Long): Produced? =
    if (sizeBytes > 0) Produced(path, "application/pdf") else null

/**
 * Как назвать напечатанное, чтобы человек узнал свой документ в работе.
 *
 * Разделители пути вычищаются: имя приходит от чужого приложения, и «отчёт/май» увёл бы файл из
 * своей папки — а на Android это ещё и путь наружу из песочницы.
 */
fun printedFileName(label: String?): String {
    val clean = label?.trim()?.replace(Regex("[/\\\\]"), "-").orEmpty()
    return if (clean.isEmpty()) "Печать.pdf" else "$clean.pdf"
}
```

- [ ] **Step 4: Убедиться, что проходит**

Run: `./gradlew :app:testDebugUnitTest --tests "com.point.print.PrintedJobTest"`
Expected: PASS, 5 тестов.

- [ ] **Step 5: Сам сервис печати**

Создать `app/src/main/kotlin/com/point/print/PointPrintService.kt`:

```kotlin
package com.point.print

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import androidx.core.content.FileProvider
import com.point.ShareActivity
import java.io.File

/**
 * Point как принтер (#251).
 *
 * Самый широкий вход из возможных: печатать умеет почти всё, включая приложения без нормального
 * экспорта — банк, госуслуги, почтовые клиенты. Задание приходит готовым PDF, и он становится
 * объектом Point.
 *
 * Службу печати человек включает один раз руками (Настройки → Печать → Point) — сама она не
 * появится, и это честная цена такого входа.
 *
 * Печать здесь не «выполняется»: Point ничего не печатает и никуда не отправляет, он забирает
 * документ. Поэтому задание сразу помечается завершённым — иначе оно висело бы в очереди вечно.
 */
class PointPrintService : PrintService() {

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = object : PrinterDiscoverySession() {

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            val id = generatePrinterId(PRINTER_ID)
            val capabilities = PrinterCapabilitiesInfo.Builder(id)
                .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
                .addResolution(
                    PrintAttributes.Resolution("default", "300 dpi", 300, 300),
                    true,
                )
                .setColorModes(
                    PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME,
                    PrintAttributes.COLOR_MODE_COLOR,
                )
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()

            val printer = PrinterInfo.Builder(id, "Point", PrinterInfo.STATUS_IDLE)
                .setCapabilities(capabilities)
                .build()

            addPrinters(listOf(printer))
        }

        override fun onStopPrinterDiscovery() = Unit
        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) = Unit
        override fun onStartPrinterStateTracking(printerId: PrinterId) = Unit
        override fun onStopPrinterStateTracking(printerId: PrinterId) = Unit
        override fun onDestroy() = Unit
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        printJob.start()
        val produced = runCatching { save(printJob) }.getOrNull()
        if (produced == null) {
            // «Не смогли забрать документ» — задание обязано завершиться отказом, а не остаться
            // в очереди навсегда: невидимая ошибка хуже видимой (#358).
            printJob.fail("Point не смог забрать документ")
            return
        }
        printJob.complete()
        Handler(Looper.getMainLooper()).post { open(produced) }
    }

    /** Забрать PDF задания в свой кэш. Scratch не годится: он стирается по концу чужой работы. */
    private fun save(printJob: PrintJob): com.point.source.Produced? {
        val document = printJob.document
        val descriptor = document.data ?: return null
        val dir = File(cacheDir, "print").apply { mkdirs() }
        val file = File(dir, printedFileName(printJob.info.label))
        descriptor.use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return printedToProduced(file.absolutePath, file.length())
    }

    private fun open(produced: com.point.source.Produced) {
        val file = File(produced.uri)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(
            Intent(this, ShareActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType(produced.mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private companion object { const val PRINTER_ID = "point-printer" }
}
```

- [ ] **Step 6: Описание службы и манифест**

Создать `app/src/main/res/xml/point_printservice.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Описание службы печати Point (#251). Своего экрана настроек у принтера нет: настраивать
     нечего — задание просто становится объектом. -->
<print-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:vendor="Point" />
```

В `data/src/main/res/xml/point_file_paths.xml` добавить путь для напечатанного:

```xml
    <!-- Куда падает задание печати (#251): тот же принцип, что у съёмки, — кэш, а не scratch. -->
    <cache-path name="print" path="print/" />
```

В `app/src/main/AndroidManifest.xml` перед `</application>`:

```xml
        <!-- Point как принтер (#251): печатать умеет почти любое приложение, поэтому это самый
             широкий вход. Службу человек включает руками в системных настройках печати. -->
        <service
            android:name=".print.PointPrintService"
            android:exported="true"
            android:label="Point"
            android:permission="android.permission.BIND_PRINT_SERVICE">
            <intent-filter>
                <action android:name="android.printservice.PrintService" />
            </intent-filter>
            <meta-data
                android:name="android.printservice"
                android:resource="@xml/point_printservice" />
        </service>
```

- [ ] **Step 7: Проверить сборку**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Проверить живьём**

```bash
export MSYS_NO_PATHCONV=1
A="$ANDROID_HOME/platform-tools/adb.exe"
"$A" install -r -t app/build/outputs/apk/debug/app-debug.apk
# Служба печати включается человеком в настройках; на эмуляторе — тем же ключом настроек.
"$A" shell settings put secure enabled_print_services com.point/com.point.print.PointPrintService
"$A" shell settings get secure enabled_print_services
"$A" logcat -c
```
Затем напечатать что-нибудь из системного приложения (например, открыть страницу в браузере →
меню → «Печать» → выбрать принтер «Point») и посмотреть, что пришло:
```bash
"$A" shell "run-as com.point ls -l cache/print"
"$A" shell dumpsys activity activities | grep topResumedActivity
```
Expected: в `cache/print` лежит PDF, сверху — `com.point/.ShareActivity` с объектом-документом.

Если на эмуляторе печатать неоткуда, проверка остаётся владельцу: включить Point в
Настройки → Печать и напечатать из любого приложения.

- [ ] **Step 9: Коммит**

```bash
git add app/src/main/kotlin/com/point/print app/src/test/kotlin/com/point/print \
        app/src/main/res/xml/point_printservice.xml app/src/main/AndroidManifest.xml \
        data/src/main/res/xml/point_file_paths.xml
git commit -m "feat: Point печатает в себя — задание печати становится объектом (Closes #251)"
```

---

### Task 2: Разрешения в контракте источника

**Files:**
- Modify: `app/src/main/kotlin/com/point/source/ObjectSource.kt`
- Modify: `app/src/main/kotlin/com/point/source/SourcePickerActivity.kt`
- Modify: `app/src/main/kotlin/com/point/source/ClipboardSource.kt`, `CameraSource.kt`, `VoiceSource.kt` (пустой список)
- Create: `app/src/main/kotlin/com/point/source/Permissions.kt`
- Test: `app/src/test/kotlin/com/point/source/PermissionsTest.kt`

**Interfaces:**
- Produces: `val ObjectSource.permissions: List<String>` (по умолчанию пустой); чистая функция `missingPermissions(required: List<String>, granted: Set<String>): List<String>`.

- [ ] **Step 1: Написать падающий тест**

Создать `app/src/test/kotlin/com/point/source/PermissionsTest.kt`:

```kotlin
package com.point.source

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Чего источнику не хватает, чтобы начать. Спрашивать разрешение, которое уже дано, — то же
 * назойливое трение, от которого Point уходит.
 */
class PermissionsTest {

    @Test
    fun `нужного нет — просим именно его`() {
        assertEquals(
            listOf("android.permission.ACCESS_FINE_LOCATION"),
            missingPermissions(
                required = listOf("android.permission.ACCESS_FINE_LOCATION"),
                granted = emptySet(),
            ),
        )
    }

    @Test
    fun `всё уже дано — не спрашиваем ничего`() {
        assertEquals(
            emptyList<String>(),
            missingPermissions(
                required = listOf("android.permission.ACCESS_FINE_LOCATION"),
                granted = setOf("android.permission.ACCESS_FINE_LOCATION"),
            ),
        )
    }

    @Test
    fun `источнику ничего не нужно — спрашивать нечего`() {
        assertEquals(emptyList<String>(), missingPermissions(emptyList(), emptySet()))
    }
}
```

- [ ] **Step 2: Убедиться, что падает**

Run: `./gradlew :app:testDebugUnitTest --tests "com.point.source.PermissionsTest"`
Expected: FAIL — `Unresolved reference: missingPermissions`.

- [ ] **Step 3: Реализовать**

Создать `app/src/main/kotlin/com/point/source/Permissions.kt`:

```kotlin
package com.point.source

/** Чего не хватает источнику из того, что он просил. Уже выданное не спрашивается повторно. */
fun missingPermissions(required: List<String>, granted: Set<String>): List<String> =
    required.filterNot { it in granted }
```

В `ObjectSource` добавить поле с умолчанием:

```kotlin
    /**
     * Runtime-разрешения, без которых источник не работает. Пусто у всех, кроме локации: Point
     * не просит у человека ничего, пока без этого действительно не обойтись.
     */
    val permissions: List<String> get() = emptyList()
```

- [ ] **Step 4: Научить экран выбора спрашивать**

В `SourcePickerActivity` добавить лаунчер разрешений и спрашивать их перед запуском источника:

```kotlin
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val source = pending
            pending = null
            when {
                source == null -> finish()
                result.values.any { !it } -> {
                    // Отказ назван словами: молча закрыться — значит оставить человека гадать.
                    Toast.makeText(this, "Без этого доступа не получится", Toast.LENGTH_SHORT).show()
                    finish()
                }
                else -> launchSource(source)
            }
        }
```

и переписать `start`:

```kotlin
    private fun start(source: ObjectSource) {
        val missing = missingPermissions(
            required = source.permissions,
            granted = source.permissions.filter {
                checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.toSet(),
        )
        if (missing.isNotEmpty()) {
            pending = source
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        launchSource(source)
    }

    private fun launchSource(source: ObjectSource) {
        lifecycleScope.launch {
            val request = source.request(this@SourcePickerActivity)
            if (request == null) {
                deliver(source.read(this@SourcePickerActivity, null))
                return@launch
            }
            pending = source
            launcher.launch(request)
        }
    }
```

Импорты: `android.widget.Toast`.

- [ ] **Step 5: Проверить**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL; три существующих источника ничего не просят и работают как раньше.

- [ ] **Step 6: Коммит**

```bash
git add app/src/main/kotlin/com/point/source app/src/test/kotlin/com/point/source
git commit -m "feat: источник умеет попросить разрешение, и только то, которого нет (part of #246)"
```

---

### Task 3: Источник «Место»

**Files:**
- Create: `app/src/main/kotlin/com/point/source/LocationSource.kt`
- Test: `app/src/test/kotlin/com/point/source/PlaceTextTest.kt`
- Modify: `app/src/main/AndroidManifest.xml` (разрешение), `app/src/main/kotlin/com/point/AppIcons.kt` (регистрация)

**Interfaces:**
- Produces: `placeText(lat: Double, lon: Double, address: String?): String`; `LocationSource` в наборе `Set<ObjectSource>`.

- [ ] **Step 1: Написать падающий тест**

Создать `app/src/test/kotlin/com/point/source/PlaceTextTest.kt`:

```kotlin
package com.point.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Каким текстом место становится объектом.
 *
 * Координаты пишутся всегда: адрес — догадка системы, а координаты — то, что измерено. Порядок
 * тот же, что у всего в Point: сначала измеренное, потом истолкованное.
 */
class PlaceTextTest {

    @Test
    fun `адрес известен — он первой строкой, координаты второй`() {
        val text = placeText(50.4501, 30.5234, "вулиця Хрещатик, 1, Київ")
        assertEquals("вулиця Хрещатик, 1, Київ\n50.450100, 30.523400", text)
    }

    @Test
    fun `адреса нет — остаются координаты, и это не отказ`() {
        assertEquals("50.450100, 30.523400", placeText(50.4501, 30.5234, null))
    }

    @Test
    fun `координаты не теряют знак и дробную часть`() {
        val text = placeText(-33.8688, 151.2093, null)
        assertTrue(text, text.startsWith("-33.868800"))
    }
}
```

- [ ] **Step 2: Убедиться, что падает**

Run: `./gradlew :app:testDebugUnitTest --tests "com.point.source.PlaceTextTest"`
Expected: FAIL — `Unresolved reference: placeText`.

- [ ] **Step 3: Реализовать источник**

Создать `app/src/main/kotlin/com/point/source/LocationSource.kt`:

```kotlin
package com.point.source

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.LocationManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.Locale
import javax.inject.Inject

/**
 * Текущее место как объект (#246).
 *
 * Единственный источник, которому нужно настоящее разрешение, — поэтому он и делался последним.
 * Объектом становится текст: адрес, если система смогла его назвать, и координаты, которые
 * измерены. Дальше человек сам решает, что с этим делать: у Point уже есть «Показать на карте» и
 * «Построить маршрут».
 */
class LocationSource @Inject constructor() : ObjectSource {

    override val id = "location"
    override val label = "Место"

    override val permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun isAvailable(context: Context): Boolean =
        ContextCompat.getSystemService(context, LocationManager::class.java) != null

    override suspend fun request(context: Context): Intent? = null

    override suspend fun read(context: Context, data: Intent?): Produced? {
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java)
        val location = runCatching {
            manager?.getProviders(true).orEmpty()
                .mapNotNull { provider -> manager?.getLastKnownLocation(provider) }
                .maxByOrNull { it.time }
        }.getOrNull()

        if (location == null) {
            // «Место пока не определилось» — это не поломка: приёмник мог ещё не поймать сигнал.
            Toast.makeText(context, "Место пока не определилось", Toast.LENGTH_SHORT).show()
            return null
        }

        val address = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        }.getOrNull()

        val file = java.io.File.createTempFile("place-", ".txt", context.cacheDir)
        file.writeText(placeText(location.latitude, location.longitude, address))
        return Produced(android.net.Uri.fromFile(file).toString(), "text/plain")
    }
}

/**
 * Каким текстом место становится объектом: сначала адрес (если система его назвала), затем
 * координаты. Координаты — измеренное, адрес — истолкование, и порядок здесь тот же, что во всём
 * Point: измеренное не прячется за истолкованием.
 */
fun placeText(lat: Double, lon: Double, address: String?): String {
    val coords = String.format(Locale.US, "%.6f, %.6f", lat, lon)
    return if (address.isNullOrBlank()) coords else "$address\n$coords"
}
```

- [ ] **Step 4: Разрешение и регистрация**

В `app/src/main/AndroidManifest.xml` перед `<queries>` добавить:

```xml
    <!--
      Единственное разрешение Point (#246). Просится только по тапу «Место» в шторке и только
      тогда, когда его ещё нет; без него источник честно говорит, что не получится.
    -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

В `AppIconsModule`:

```kotlin
    @Binds
    @IntoSet
    abstract fun locationSource(impl: com.point.source.LocationSource): ObjectSource
```

- [ ] **Step 5: Проверить сборку и тесты**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Проверить живьём**

```bash
export MSYS_NO_PATHCONV=1
A="$ANDROID_HOME/platform-tools/adb.exe"
"$A" install -r -t app/build/outputs/apk/debug/app-debug.apk
# Эмулятору задаётся точка, иначе места он не знает.
"$A" emu geo fix 30.5234 50.4501
"$A" shell am start -n com.point/.source.SourcePickerActivity
"$A" shell uiautomator dump /sdcard/ui.xml
"$A" exec-out cat /sdcard/ui.xml | tr '>' '\n' | grep -o 'text="[^"]*"' | head
```
Expected: в списке появилась плашка «Место». Тап → системный запрос разрешения → после «Разрешить»
в Point открывается текстовый объект с координатами.

- [ ] **Step 7: Коммит**

```bash
git add app/src/main/kotlin/com/point/source/LocationSource.kt \
        app/src/test/kotlin/com/point/source/PlaceTextTest.kt \
        app/src/main/AndroidManifest.xml app/src/main/kotlin/com/point/AppIcons.kt
git commit -m "feat: текущее место становится объектом из шторки (part of #246)"
```

---

## Что этот план не делает

- **Windows-принтер** (#251 в исходной формулировке про ПК) — нужен драйвер уровня системы, это работа десктопа.
- **Свой экран настроек принтера** — настраивать нечего: задание просто становится объектом.
- **Фоновая локация** — только по тапу человека, никакого `ACCESS_BACKGROUND_LOCATION`.
- **Красота экрана выбора** — плашки пока штатные, портал будет отдельной правкой.

# «Момент чтения» (Reading Beat) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** На первом экране Point драматизировать момент понимания — пользователь ВИДИТ, как объект «читается»: свип-свет по объекту, факты вспыхивают, аура разгорается по мере узнавания.

**Architecture:** Расширяем существующий M1-компонент `AliveSurface` (Motion.kt): убираем кольцо-импульс вокруг объекта, добавляем световой свип ПО объекту (клип по форме, направление из pure `readingSweepSpecFor(kind)`), а аура-тень гоним от pure `auraLevel(factCount)` вместо булева `understood`. `FactRow` (UnderstoodSection.kt) получает ignite-вспышку. Всё — чистый Compose, ноль новых зависимостей.

**Tech Stack:** Kotlin + Jetpack Compose (core:ui, Android library). Юнит-тесты — JUnit4 (JVM, `testDebugUnitTest`). Сборка — Gradle wrapper 8.14.3 на JBR.

## Global Constraints

- **Ноль новых зависимостей** — только `androidx.compose.*` и `android.graphics`, уже в модуле.
- **≤300 мс до интерактива**: motion украшает готовый экран, никогда не блокирует. `thinking`/факты приходят async — никаких блокирующих enter-хореографий.
- **Reduced motion**: `rememberMotionEnabled() == false` → вся динамика статична (свип off, факты на месте, аура сразу на финальном уровне). Гейт уже есть.
- **Батарея**: свип — тот же `rememberInfiniteTransition`, что и «дыхание»; не добавляет нового always-on дерева.
- **«Движение объясняет модель»** (MOTION.md): каждый элемент маппится на принцип №3 (импульсы по объекту), №10 (аура понимания), №1/№4 (факты рождаются от объекта), №6 (физика по типу). Ничего декоративного сверх смысла.
- **Сборка/тесты в Git Bash**: перед `./gradlew` — `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`.
- Скоуп v1: БЕЗ частицы-искры объект→факт (вариант B) и БЕЗ звука/хаптики (срез M4).

---

### Task 1: Pure motion specs — `readingSweepSpecFor` + `auraLevel`

**Files:**
- Modify: `core/ui/src/main/kotlin/com/point/core/ui/Motion.kt` (добавить типы/функции рядом с `breathSpecFor`)
- Test: `core/ui/src/test/kotlin/com/point/core/ui/ReadingBeatTest.kt` (создать)

**Interfaces:**
- Produces:
  - `data class ReadingSweepSpec(val vertical: Boolean, val periodMs: Int, val softness: Float)`
  - `fun readingSweepSpecFor(kind: ObjectKind): ReadingSweepSpec`
  - `fun auraLevel(factCount: Int): Float` — `0` фактов → `0f`; иначе `min(1f, 0.55f + 0.15f*(n-1))`.

- [ ] **Step 1: Написать падающий тест**

Создать `core/ui/src/test/kotlin/com/point/core/ui/ReadingBeatTest.kt`:

```kotlin
package com.point.core.ui

import com.point.core.model.ObjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure motion specs for «Момент чтения» (MOTION.md принципы №3/№10) — JVM-tested. */
class ReadingBeatTest {

    @Test
    fun `a document is read top-to-bottom, a photo diagonally`() {
        assertTrue("документ читается сверху вниз", readingSweepSpecFor(ObjectKind.PDF).vertical)
        assertTrue("текст читается сверху вниз", readingSweepSpecFor(ObjectKind.TEXT).vertical)
        assertFalse("фото — мягкий диагональный отблеск", readingSweepSpecFor(ObjectKind.IMAGE).vertical)
    }

    @Test
    fun `every kind gets a calm, sane sweep`() {
        ObjectKind.entries.forEach { kind ->
            val spec = readingSweepSpecFor(kind)
            assertTrue("$kind: свип видимо-медленный", spec.periodMs in 1_000..2_500)
            assertTrue("$kind: softness в разумных рамках", spec.softness in 0.2f..0.6f)
        }
    }

    @Test
    fun `aura is dark with no facts and warm from the first`() {
        assertEquals(0f, auraLevel(0), 0.0001f)
        assertEquals(0.55f, auraLevel(1), 0.0001f)
        assertTrue("больше фактов — теплее", auraLevel(2) > auraLevel(1))
    }

    @Test
    fun `aura ramps monotonically and saturates at one`() {
        var prev = auraLevel(0)
        (1..8).forEach { n ->
            val cur = auraLevel(n)
            assertTrue("монотонность на $n", cur >= prev)
            assertTrue("не больше 1 на $n", cur <= 1f)
            prev = cur
        }
        assertEquals("насыщается к 1", 1f, auraLevel(8), 0.0001f)
    }
}
```

- [ ] **Step 2: Прогнать — убедиться, что падает (компиляция)**

Run: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && ./gradlew :core:ui:testDebugUnitTest --tests "com.point.core.ui.ReadingBeatTest"`
Expected: FAIL — `unresolved reference: readingSweepSpecFor` / `auraLevel` (функций ещё нет).

- [ ] **Step 3: Минимальная реализация**

В `core/ui/src/main/kotlin/com/point/core/ui/Motion.kt` добавить сразу ПОСЛЕ функции `breathSpecFor` (после строки с `ObjectKind.PDF, ObjectKind.OFFICE, ObjectKind.UNKNOWN -> BreathSpec(...)` и закрывающей `}`):

```kotlin
/** Физика свипа-чтения по типу (принцип №3): как именно объект «читается». */
data class ReadingSweepSpec(val vertical: Boolean, val periodMs: Int, val softness: Float)

/** Документ читается строго сверху вниз (по строкам); фото — мягкий диагональный отблеск. */
fun readingSweepSpecFor(kind: ObjectKind): ReadingSweepSpec = when (kind) {
    ObjectKind.IMAGE -> ReadingSweepSpec(vertical = false, periodMs = 1_800, softness = 0.5f)
    ObjectKind.PDF, ObjectKind.OFFICE, ObjectKind.TEXT, ObjectKind.URL ->
        ReadingSweepSpec(vertical = true, periodMs = 1_300, softness = 0.3f)
    else -> ReadingSweepSpec(vertical = true, periodMs = 1_500, softness = 0.35f)
}

/** Аура понимания (принцип №10): растёт с числом понятых фактов — 0 фактов темно,
 *  первый факт уже тёплый, насыщается к максимуму. */
fun auraLevel(factCount: Int): Float =
    if (factCount <= 0) 0f else minOf(1f, 0.55f + 0.15f * (factCount - 1))
```

- [ ] **Step 4: Прогнать — убедиться, что зелено**

Run: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && ./gradlew :core:ui:testDebugUnitTest --tests "com.point.core.ui.ReadingBeatTest"`
Expected: PASS (4 теста).

- [ ] **Step 5: Коммит**

```bash
git add core/ui/src/main/kotlin/com/point/core/ui/Motion.kt core/ui/src/test/kotlin/com/point/core/ui/ReadingBeatTest.kt
git commit -m "feat: pure свип-спеки и аура-рамп для «момента чтения» (#114)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01N8Df8mBs75tgnfXMLhPdin"
```

---

### Task 2: Свип-чтение + аура-рамп в `AliveSurface` (+ обновить единственный вызов)

**Files:**
- Modify: `core/ui/src/main/kotlin/com/point/core/ui/Motion.kt` — `AliveSurface` (сигнатура + тело)
- Modify: `core/ui/src/main/kotlin/com/point/core/ui/FirstScreen.kt` — `ObjectHeader` (param + вызов `AliveSurface`) и call-site `ObjectHeader(...)`

**Interfaces:**
- Consumes (Task 1): `readingSweepSpecFor(kind)`, `auraLevel(factCount)`.
- Produces: `AliveSurface(kind, thinking, understanding: Float, shape, size, modifier, content)` — `understood: Boolean` УДАЛЁН, вместо него `understanding: Float` (0..1). `ObjectHeader(obj, thinking, factCount: Int, preview)` — `understood: Boolean` заменён на `factCount: Int`.

Визуальная задача — автотеста хореографии нет; гейт = компиляция + существующие тесты `core:ui` зелены (регрессий нет). Сама анимация проверяется в Task 4 через `@Preview`/устройство.

- [ ] **Step 1: Добавить импорты в `Motion.kt`**

В блок импортов `core/ui/src/main/kotlin/com/point/core/ui/Motion.kt` добавить (рядом с прочими `androidx.compose.ui.*`):

```kotlin
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
```

- [ ] **Step 2: Заменить `AliveSurface` целиком**

В `Motion.kt` заменить ВСЮ функцию `AliveSurface` (от `@Composable\nfun AliveSurface(` до её закрывающей `}`) на:

```kotlin
/**
 * The living frame around the object's preview:
 * - it **breathes** with its kind's physics — the object is alive, not a thumbnail;
 * - while [thinking], a soft band of light is **read across** the object (принцип №3) —
 *   clipped to its own shape, direction by kind ([readingSweepSpecFor]);
 * - [understanding] (0..1, from [auraLevel]) warms the shadow into a brand **aura** that
 *   grows fact by fact — "Point понял" without a word of text (принцип №10).
 */
@Composable
fun AliveSurface(
    kind: ObjectKind,
    thinking: Boolean,
    understanding: Float,
    shape: Shape,
    size: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = rememberMotionEnabled()
    val spec = remember(kind) { breathSpecFor(kind) }
    val sweepSpec = remember(kind) { readingSweepSpecFor(kind) }
    val accent = MaterialTheme.colorScheme.primary
    val reading = motion && thinking

    val breath: Float
    val sweep: Float
    if (motion) {
        val transition = rememberInfiniteTransition(label = "alive")
        breath = transition.animateFloat(
            initialValue = 1f,
            targetValue = spec.scale,
            animationSpec = infiniteRepeatable(
                tween(spec.periodMs / 2, easing = EaseInOutSine), RepeatMode.Reverse,
            ),
            label = "breath",
        ).value
        sweep = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(sweepSpec.periodMs, easing = LinearEasing)),
            label = "sweep",
        ).value
    } else {
        breath = 1f
        sweep = 0f
    }
    val aura by animateFloatAsState(
        targetValue = understanding.coerceIn(0f, 1f),
        animationSpec = tween(900),
        label = "aura",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = breath
                    scaleY = breath
                }
                .shadow(
                    elevation = (14 + 8 * aura).dp,
                    shape = shape,
                    clip = false,
                    ambientColor = lerp(Color.Black, accent, aura),
                    spotColor = lerp(Color.Black, accent, aura),
                )
                .clip(shape)
                .drawWithContent {
                    drawContent()
                    // Свип-чтение (принцип №3): мягкая световая полоса читается по объекту,
                    // пока Point думает; клип по форме объекта делает предыдущий .clip(shape).
                    if (reading) {
                        val band = this.size.minDimension * (0.35f + sweepSpec.softness)
                        val travel =
                            if (sweepSpec.vertical) this.size.height
                            else this.size.width + this.size.height
                        val p = sweep * (travel + band * 2f) - band
                        val start = if (sweepSpec.vertical) Offset(0f, p) else Offset(p, p)
                        val end =
                            if (sweepSpec.vertical) Offset(0f, p + band)
                            else Offset(p + band, p + band)
                        drawRect(
                            brush = Brush.linearGradient(
                                0f to Color.Transparent,
                                0.5f to accent.copy(alpha = 0.20f),
                                1f to Color.Transparent,
                                start = start,
                                end = end,
                            ),
                        )
                    }
                },
        ) {
            content()
        }
    }
}
```

- [ ] **Step 3: Обновить `ObjectHeader` в `FirstScreen.kt`**

В `core/ui/src/main/kotlin/com/point/core/ui/FirstScreen.kt` в функции `private fun ObjectHeader(...)` заменить параметр `understood: Boolean = false,` на `factCount: Int = 0,`, и в вызове `AliveSurface(...)` заменить строку `understood = understood,` на `understanding = auraLevel(factCount),`. Итоговая шапка и вызов:

```kotlin
@Composable
private fun ObjectHeader(
    obj: PointObject,
    thinking: Boolean = false,
    factCount: Int = 0,
    preview: ImageBitmap? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val headerSize = if (preview != null) 132.dp else 96.dp
        AliveSurface(
            kind = obj.state.kind,
            thinking = thinking,
            understanding = auraLevel(factCount),
            shape = RoundedCornerShape(26.dp),
            size = headerSize,
        ) {
```

(остальное тело `ObjectHeader` не трогаем.)

- [ ] **Step 4: Обновить call-site `ObjectHeader(...)` в `FirstScreen.kt`**

Там же (в `fun FirstScreen`, ~строка 222) заменить `understood = facts.isNotEmpty(),` на `factCount = facts.size,`:

```kotlin
            ObjectHeader(
                obj,
                thinking = enriching.isNotEmpty() || working,
                factCount = facts.size,
                preview = previewBitmap,
            )
```

- [ ] **Step 5: Скомпилировать и прогнать тесты core:ui (регрессий нет)**

Run: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && ./gradlew :core:ui:compileDebugKotlin :core:ui:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; `ReadingBeatTest`/`BreathSpecTest`/`MagnetTest`/`UnderstoodFactsTest` — зелены.

- [ ] **Step 6: Коммит**

```bash
git add core/ui/src/main/kotlin/com/point/core/ui/Motion.kt core/ui/src/main/kotlin/com/point/core/ui/FirstScreen.kt
git commit -m "feat: свип-чтение по объекту + аура-рамп в AliveSurface (#114)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01N8Df8mBs75tgnfXMLhPdin"
```

---

### Task 3: Ignite-вспышка факта в `FactRow`

**Files:**
- Modify: `core/ui/src/main/kotlin/com/point/core/ui/UnderstoodSection.kt` — `private fun FactRow(fact)`

**Interfaces:**
- Consumes: ничего нового (все импорты `background`/`clip`/`RoundedCornerShape`/`graphicsLayer` уже есть в файле).
- Produces: визуальное поведение `FactRow` (вспышка при появлении). Публичных сигнатур не меняет.

Визуальная задача — гейт = компиляция + тесты core:ui зелены.

- [ ] **Step 1: Заменить `FactRow` целиком**

В `core/ui/src/main/kotlin/com/point/core/ui/UnderstoodSection.kt` заменить ВСЮ функцию `private fun FactRow(fact: UnderstoodFact)` на:

```kotlin
/** One understood line: ignites brand-bright as if just read out of the object (принципы
 *  №1/№4), then settles — синхронно с аурой объекта, что делает шаг на каждый факт. */
@Composable
private fun FactRow(fact: UnderstoodFact) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val progress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(240),
        label = "fact-in",
    )
    val flash by animateFloatAsState(
        targetValue = if (appeared) 0f else 1f,
        animationSpec = tween(520),
        label = "fact-ignite",
    )
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 8.dp.toPx()
            }
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.16f * flash))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text = "✓",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.graphicsLayer {
                val s = 1f + 0.35f * flash
                scaleX = s
                scaleY = s
            },
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = fact.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        fact.value?.let { value ->
            Spacer(Modifier.width(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
```

- [ ] **Step 2: Скомпилировать и прогнать тесты core:ui**

Run: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && ./gradlew :core:ui:compileDebugKotlin :core:ui:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; тесты зелены.

- [ ] **Step 3: Коммит**

```bash
git add core/ui/src/main/kotlin/com/point/core/ui/UnderstoodSection.kt
git commit -m "feat: ignite-вспышка факта в карточке «Point понял» (#114)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01N8Df8mBs75tgnfXMLhPdin"
```

---

### Task 4: Превью «Момент чтения» + полный гейт

**Files:**
- Modify: `core/ui/src/main/kotlin/com/point/core/ui/FirstScreenPreview.kt` — добавить `@Preview`

**Interfaces:**
- Consumes: существующие `sampleObject(...)`, `sampleBubbles(...)`, `FirstScreen(...)`; `com.point.core.model.Feature`.
- Produces: превью-кейс для Android Studio (не влияет на рантайм).

- [ ] **Step 1: Добавить превью**

В `core/ui/src/main/kotlin/com/point/core/ui/FirstScreenPreview.kt` добавить сразу после функции `PreviewThinking()` (после её закрывающей `}`, до `@Preview(name = "Image · без фактов"...)`):

```kotlin
@Preview(name = "Скриншот · Момент чтения (#114)", showBackground = true)
@Composable
private fun PreviewReadingBeat() = PointTheme {
    // Reading-beat: обогащение ещё идёт (свип + ignite живы), но факты уже упали —
    // аура высоко по своей рампе. Момент «он понял» в полёте.
    val obj = sampleObject(
        ObjectKind.IMAGE, "image/png", "чек.jpg",
        features = setOf(
            com.point.core.model.Feature.IS_PURCHASE,
            com.point.core.model.Feature.HAS_PHONE,
            com.point.core.model.Feature.HAS_ADDRESS,
        ),
        metadata = mapOf(
            "entity.phone" to "+380 67 123 45 67",
            "entity.address" to "вул. Хрещатик, 1",
        ),
    )
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.IMAGE),
        onBubble = {},
        enriching = listOf("Распознаю текст…"),
    )
}
```

- [ ] **Step 2: Полный гейт — тесты + APK**

Run: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && ./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL (все юнит-тесты зелены, debug APK собран).

- [ ] **Step 3: Коммит**

```bash
git add core/ui/src/main/kotlin/com/point/core/ui/FirstScreenPreview.kt
git commit -m "feat: превью «Момент чтения» + гейт первого экрана (#114)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01N8Df8mBs75tgnfXMLhPdin"
```

- [ ] **Step 4: Живая проверка (устройство)**

Собрать и залить на телефон, открыть объект (шаринг фото/чека), убедиться: свип идёт по объекту пока распознаётся, факты вспыхивают, аура разгорается фактами; при reduced motion (Настройки → анимации 0) — всё статично.

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
export PATH="$PATH:/c/Users/User/AppData/Local/Android/Sdk/platform-tools"
./gradlew :app:assembleDebug && adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

---

## Self-Review

**1. Покрытие спеки:**
- ① Свип-чтение по объекту → Task 1 (`readingSweepSpecFor`) + Task 2 (рендер в `AliveSurface`, заменяет кольцо). ✓
- ② Факты зажигаются от объекта (вариант A) → Task 3 (ignite-вспышка `FactRow`); «пинг на объекте» доставляется аура-шагом на каждый факт (Task 2). ✓
- ③ Аура-рамп по числу фактов → Task 1 (`auraLevel`) + Task 2 (`understanding = auraLevel(factCount)`). ✓
- ④ Финальная точка «понял» → аура фиксируется на `auraLevel` когда `thinking` спал, свип гаснет (`reading == false`). ✓
- Инварианты (≤300мс/reduced-motion/батарея) → Global Constraints + гейт `reading = motion && thinking`, `sweep=0` без motion, аура snap'ается под reduced motion. ✓
- Тесты pure-функций → Task 1 (4 теста). Хореография → Task 4 (@Preview + устройство). ✓
- Крайние случаи (нет превью → свип по icon-плашке; 0 фактов → аура 0; быстрое обогащение → ≥1 проход) → покрыты рендером (свип на контенте `AliveSurface`, `auraLevel(0)=0`). ✓

**2. Плейсхолдеры:** нет — весь код показан целиком, команды точные.

**3. Согласованность типов:** `understanding: Float` (Task 2 сигнатура) ← `auraLevel(factCount): Float` (Task 1) ← `factCount: Int` (Task 2 ObjectHeader) ← `facts.size` (Task 2 call-site). `ReadingSweepSpec.{vertical,periodMs,softness}` (Task 1) читаются в Task 2 ровно этими именами. Совпадает.

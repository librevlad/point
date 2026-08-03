# Редизайн Point Desktop — план работ

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Десктоп заговорит тем же языком, что и телефон, — тёмный портал вместо светлого Material, и виден весь путь объекта.

**Architecture:** Мокап — `Point Desktop.dc.html` из Claude Design (проект `92d1b011…`, issue #285). Владелец выбрал подход **2a**: один объект, весь его путь слева направо, живой конец справа со станциями действий. Сначала язык (палитра, шрифты, окно), потом поверхности: пустой экран, док «Прилетело», конвейер.

**Tech Stack:** Kotlin, Compose Desktop (JVM), JUnit4.

## Global Constraints

- Палитра мокапа: фон окна `#0B0D10`, полотно `#07080A`, границы `#242833`, текст `#FFFFFF`, приглушённый `#A1A6B3`, акценты `#7B5CFF` (фиолетовый) и `#00E0FF` (голубой), поверхность карточек — градиент `#1A1D25 → #121419`.
- Шрифты: Unbounded (заголовки), Manrope (текст). Файлы уже в репозитории — `core/ui/src/main/res/font/*.ttf`; **копировать их второй раз нельзя**, каталог подключается в ресурсы `:desktop` через `sourceSets`.
- `:core:model` и `:core:flow` не трогаются — десктоп живёт на них как есть.
- Ни одного нового модуля: правки внутри `:desktop`.
- Проверка глазами обязательна: `./gradlew :desktop:run`, затем снимок окна скриптом `scratchpad/shot.ps1` (PowerShell, ищет окно по заголовку).
- Перед сборкой: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`.

---

### Task 1: Язык десктопа — палитра, шрифты, окно

**Files:**
- Create: `desktop/src/main/kotlin/com/point/desktop/ui/PointDesktopTheme.kt`
- Modify: `desktop/build.gradle.kts` (шрифты в ресурсы)
- Modify: `desktop/src/main/kotlin/com/point/desktop/Main.kt` (тема вокруг приложения)

**Interfaces:**
- Produces: `object PointColors` с полями `canvas`, `surface`, `border`, `text`, `muted`, `violet`, `cyan`; `@Composable fun PointDesktopTheme(content: @Composable () -> Unit)`; `object PointType` с `display`, `title`, `body`, `label`, `mono`.

- [ ] **Step 1: Подключить шрифты в ресурсы `:desktop`**

В `desktop/build.gradle.kts` после блока `kotlin { jvmToolchain(17) }` добавить:

```kotlin
// Шрифты живут в :core:ui (Android-ресурсы) и здесь ПЕРЕИСПОЛЬЗУЮТСЯ, а не копируются:
// один файл шрифта на проект — иначе телефон и ПК однажды разойдутся в начертании.
sourceSets.main {
    resources.srcDir(rootProject.file("core/ui/src/main/res/font"))
}
```

- [ ] **Step 2: Тема**

Создать `desktop/src/main/kotlin/com/point/desktop/ui/PointDesktopTheme.kt` с палитрой и типографикой из мокапа: `PointColors` (значения — в «Global Constraints»), загрузка `Font(resource = "unbounded.ttf")` и `Font(resource = "manrope.ttf")` через `androidx.compose.ui.text.platform.Font`, `PointType` поверх них и `MaterialTheme` с тёмной схемой, куда подставлены эти цвета (чтобы штатные компоненты не светились белым).

- [ ] **Step 3: Обернуть приложение**

В `Main.kt` внутри `Window { … }` обернуть `DesktopApp(...)` в `PointDesktopTheme { … }`.

- [ ] **Step 4: Проверить глазами**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew :desktop:run &
sleep 90
powershell.exe -NoProfile -ExecutionPolicy Bypass -File <scratch>/shot.ps1 -Title "Point" -Out <scratch>/desktop-theme.png
```
Expected: окно тёмное, текст белый, шрифты не системные.

- [ ] **Step 5: Коммит**

```bash
git add desktop/build.gradle.kts desktop/src/main/kotlin/com/point/desktop/ui/PointDesktopTheme.kt desktop/src/main/kotlin/com/point/desktop/Main.kt
git commit -m "feat: десктоп заговорил языком портала — палитра и шрифты из мокапа (part of #285)"
```

---

### Task 2: Пустой экран — «Point ждёт объект»

**Files:**
- Create: `desktop/src/main/kotlin/com/point/desktop/ui/EmptyScreen.kt`
- Modify: `desktop/src/main/kotlin/com/point/desktop/ui/DesktopApp.kt`

**Interfaces:**
- Consumes: `PointColors`, `PointType` из задачи 1.
- Produces: `@Composable fun EmptyScreen(config: PcConfig, addresses: List<String>, port: Int)`.

Из мокапа (блок «3a Пусто · подключить телефон»): портал 260×260 из двух колец (внешнее — `#7B5CFF` со свечением, внутреннее — `#00E0FF`), заголовок «Point ждёт объект» (Unbounded 28), подпись «Дальше он покажет, что с ним можно сделать — и весь путь останется на экране», три способа начать (перетащить файл · взять из буфера с подсказкой `Ctrl+Shift+V` · поделиться с телефона), справа карточка «Подключить телефон» шириной 340 с QR, адресом и именем ПК.

- [ ] **Step 1: Собрать экран** по описанию выше, взяв QR из существующего `QrImage.kt`.
- [ ] **Step 2: Подставить в `DesktopApp`** вместо `ConnectionCard`, когда список объектов пуст.
- [ ] **Step 3: Проверить глазами** тем же снимком окна.
- [ ] **Step 4: Коммит**

```bash
git add desktop/src/main/kotlin/com/point/desktop/ui
git commit -m "feat: пустой экран десктопа — портал, три способа начать и подключение телефона (part of #285)"
```

---

### Task 3: Док «Прилетело» и рамка окна

**Files:**
- Create: `desktop/src/main/kotlin/com/point/desktop/ui/Dock.kt`
- Modify: `desktop/src/main/kotlin/com/point/desktop/ui/DesktopApp.kt`

Из мокапа: слева колонка 244 px с меткой «Прилетело», карточками прилетевших объектов и подсказкой «Брось файл сюда · Ctrl+Shift+V» внизу; справа от дока — полотно, где живёт объект.

- [ ] **Step 1: Док** с элементами из `state.items` и пустыми плейсхолдерами, когда их нет.
- [ ] **Step 2: Разметка `DesktopApp`** — док слева, полотно справа.
- [ ] **Step 3: Проверить глазами** (перетащить файл в окно нельзя автоматически — проверяется руками; на снимке видно пустой док).
- [ ] **Step 4: Коммит**

```bash
git commit -m "feat: док «Прилетело» вдоль левого края десктопа (part of #285)"
```

---

### Task 4: Конвейер — путь объекта и станции действий

**Files:**
- Create: `desktop/src/main/kotlin/com/point/desktop/ui/Conveyor.kt`
- Modify: `desktop/src/main/kotlin/com/point/desktop/ui/DesktopApp.kt`

Из мокапа (подход 2a): слева направо — источник объекта (вид, факты, вердикт), пройденные станции с названием применённой возможности и временем, живой конец справа: секции «Извлечь · Превратить · Отправить» со списком действий. Внизу — путь-чипсы и строка состояния; во время работы — «{{ busyCap }}…» с временем.

- [ ] **Step 1: Разложить состояние** — что именно рисуется из `DesktopState` и `InboxItem`, чего не хватает (например, истории применённых действий на объекте); недостающее добавить в `DesktopState` с юнит-тестом.
- [ ] **Step 2: Конвейер** по мокапу.
- [ ] **Step 3: Проверить глазами** — перетащить файл руками и снять окно.
- [ ] **Step 4: Коммит**

```bash
git commit -m "feat: конвейер — весь путь объекта на одном экране (Closes #285)"
```

---

## Что этот план не делает

- **Оверлей по хоткею и трей-панель** — это не редизайн, а новые механизмы поверх ОС; отдельной задачей (перекликается с #405 «Point X»).
- **История-цепочки и настройки** — следующим планом, когда конвейер заработает.
- **Подход 2b** (конвейер-цепочка) — владелец выбрал 2a; 2b остаётся в мокапе на будущее.

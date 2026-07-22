# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Что это

**Point** — Android-приложение (Kotlin/Compose), которое встраивается в системный
Share: пользователь «шарит» объект (image / text / pdf / zip / url), а приложение
показывает **пузырьки действий** вокруг него. Модель взаимодействия — путешествие
по *состояниям объекта*, а не по приложениям. Никаких меню, списков функций,
настроек, чатов.

Первоисточник требований — `point-v0.2-spec.md` (в корне). Проектные решения — в
`docs/DECISIONS.md`, карта архитектуры — в `docs/ARCHITECTURE.md`, платформенное
направление (CTO-видение, фазы, швы) — в `docs/ROADMAP.md`. **Читай их перед
работой** — многое из «почему именно так» живёт там, а не в коде.

## Как здесь работать (важно)

- **Спека — design-first.** Сначала архитектура, код — после подтверждения.
  Срезы 1–2 уже реализованы и собираются (см. «Статус» в `docs/ARCHITECTURE.md`).
  Следующие шаги (напр. извлечение текста из PDF, персист флоу) — не начинай без
  явного «го».
- **Работать максимально автономно** — принимать разумные решения самому, а не
  засыпать вопросами. Спорные развилки фиксируй в `docs/DECISIONS.md`.
- **НЕ МУСОРИТЬ В КОРНЕ.** В корне — только то, что обязательно для Gradle
  (`settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/`,
  `.gitignore`), плюс `CLAUDE.md`, спека и `docs/`. Временные файлы, черновики,
  логи, эксперименты — в системный temp/scratch, не в репозиторий.

## Сборка и тесты

Тулчейн настроен и сборка проверена (BUILD SUCCESSFUL, APK собирается):
- **Gradle wrapper 8.14.3** в репозитории (`./gradlew`).
- **JDK**: `java`/`gradle` не в системном PATH; Gradle крутится на встроенном JBR
  Android Studio (`C:\Program Files\Android\Android Studio\jbr`, OpenJDK 21).
  Тулчейн JDK 17 (`jvmToolchain(17)`) автопровизионит **foojay-resolver** (в
  `settings.gradle.kts`).
- **`local.properties`** (git-ignored) создан: `sdk.dir` + `GEMINI_API_KEY`.

CLI-сборка (Git Bash / инструмент Bash; в PowerShell — `.\gradlew.bat`) требует
указать JDK, т.к. в PATH его нет:

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"   # для каждой сессии шелла
./gradlew test assembleDebug          # всё + unit-тесты + debug APK  ← основная проверка
./gradlew :app:installDebug           # установить на устройство/эмулятор
./gradlew test                        # только unit-тесты (быстро, без эмулятора)
./gradlew :executors:test --tests "com.point.executors.DefaultCapabilityRegistryTest"  # один класс
./gradlew clean
```

Android Studio использует свой JBR автоматически — там `JAVA_HOME` не нужен.

У приложения **нет иконки в лаунчере** — единственная точка входа `ShareActivity`
(получает системный Share). «Запустить» = установить и «расшарить» в него файл.

### Тестировать без перезаливки APK

Полный гайд — `docs/TESTING.md`. Кратко:
- **Compose `@Preview`** (`core/ui/.../FirstScreenPreview.kt`) — весь первый экран
  рендерится в Android Studio без устройства и APK. Главный инструмент для UI.
- **`SandboxActivity`** — debug-only иконка «Point Sandbox» в лаунчере; кормит
  реальный флоу примерами объектов без «расшаривания». Ставится один раз, дальше
  **Apply Changes / Live Edit** без переустановки.
- **JVM-тесты** (`:core:flow:test`, `:executors:test`) — логика без эмулятора.

Android SDK: `C:\Users\User\AppData\Local\Android\Sdk`. Установлена платформа
**только android-36**, поэтому `compileSdk = 36` жёстко. Версии всего — в
`gradle/libs.versions.toml` (единственное место правки). На первом синке AS может
предложить апгрейд AGP/Kotlin — это ожидаемо, принимай и правь каталог.

## Модули и правило зависимостей

```
:core:model  ← :core:flow ← { :data, :executors, :core:ui } ← :app
```

Стрелки только вниз. Полная схема и таблица — в `docs/ARCHITECTURE.md`.

- `:core:model` — **чистый Kotlin, без единого Android API.** Модель, состояния,
  результаты. Тестируется напрямую.
- `:core:flow` — **чистый Kotlin.** Контракты `Capability` (что), `Realizer`
  (как), `Resolver`, `CapabilityRegistry`, `BubblePolicy`, `ObjectStore`,
  `LlmClient` + вывод Flow Graph.
- `:core:ui` — Compose Bubble UI, дизайн-система. Ноль бизнес-логики.
- `:data` — реализации `ObjectStore` (scratch) и `LlmClient` (Gemini/OpenAI+fallback).
- `:executors` — каждое действие = `*Capability` (декларация) + `*Realizer`
  (поведение); регистрация через Hilt `@IntoSet`.
- `:app` — Share Activity, Compose host, стек-навигация, DI-wiring.

## Инварианты, которые легко нарушить

- **Capability ≠ Realizer.** `Capability` = *что можно* (декларация: accepts/
  produces/meta) — её видят UI/FlowGraph/BubblePolicy. `Realizer` = *как* (за
  `Resolver`'ом) — сюда завтра встанут AI/cloud/ICG-реализации, UI не меняется.
  Никогда не тащи `execute`/реализацию в то, что видит UI.
- **Flow Graph выводится, не хранится.** Пузырьки для состояния = Capability, у
  кого `accepts(state) == true`, отранжированные `BubblePolicy`. Таблицы переходов
  нет — добавил Capability, граф расширился. `produces` — только подсказка;
  истинное следующее состояние переклассифицируется из реального выхода.
- **`:core:model` и `:core:flow` держи Android-free.** Поэтому scratch-ссылка —
  `ScratchRef(String)`, а `ObjectStore.ingest` берёт `String`, а не
  `android.net.Uri` (Uri стрингуется на границе `:app`, парсится обратно в
  `:data`). Не тащи `android.*` в core — сломаешь юнит-тестируемость.
- **Никаких глобальных/статических синглтонов и `object`-паттернов.** App-scoped
  Hilt-биндинги (registry, scratch-store, Gemini-клиент) — легитимны, это DI, а не
  Singleton.
- **Каждый side-effect — за интерфейсом.** File IO, сеть, Android framework,
  PDF-рендер — за контрактом; в тестах подставляются fakes. Pure-логику тестируем
  напрямую (без Robolectric).
- **Объект копируется в scratch немедленно при приёме.** Работаем только с копией,
  не с `content://` Uri из Share (его read-grant умирает с Activity). По окончании
  флоу — обязательный `ObjectStore.clear()`, даже при потере флоу на process death.
- **Первый экран ≤ 300 мс, без I/O** — только нулевые сигналы (MIME/расширение/
  размер). Обогащение признаков (peek в PDF/ZIP) — async, дополняет пузырьки позже.
  **LLM никогда не на первом экране** — `AiRealizer` только после выбора действия
  (`CapabilityMeta.network=true` держит его вне ≤300 мс).
- **Результат шага — sealed `ActionResult`** (`Success`/`Done`/`Failure(recoverable)`/
  `NeedsInput`). `Done` — терминальное действие без нового объекта (Share/Save).
  Не глотай ошибки: невидимая цепочка без явного канала ошибки — ловушка доверия.
- **Терминальные действия — без Activity.** Share/Save работают через
  `@ApplicationContext` (FileProvider + `startActivity(NEW_TASK)` для Share,
  MediaStore для Save) за контрактами `Sharer`/`Exporter`. Не тащи Activity/Context
  в realizer — инжектируй контракт.

## Секреты

Ключ Gemini — в `local.properties` (git-ignored) как `GEMINI_API_KEY`; в код
попадает через `BuildConfig.GEMINI_API_KEY` (плумбинг в `data/build.gradle.kts`).
Никогда не хардкодь ключ и не коммить `local.properties`. Шаблон —
`local.properties.sample`.

Без ключей `AI`/`Перевод` вернут понятную ошибку. Провайдеры — `GeminiLlmClient`
(`gemini-2.0-flash`) → `OpenAiLlmClient` (OpenAI-совместимый), связанные
`FallbackLlmClient` (первый успех выигрывает, чинит 429). Ключи/URL/модель — в
`local.properties` (см. `local.properties.sample`).

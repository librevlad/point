# Point v0.2 — Уточнённое ТЗ (design-first бриф для Claude Code)

## Роль
Ты — Principal Android Engineer и Software Architect. Сначала спроектируй
архитектуру, предложи улучшения с компромиссами, и только после моего
подтверждения пиши код.

---

## Что изменено относительно v0.1 (и почему)

1. **Flow Graph — не отдельная захардкоженная таблица переходов, а вывод из
   I/O-контрактов Executor'ов.** Каждый Executor декларирует, какие входные
   состояния принимает и какое выходное производит; набор пузырьков =
   «какие Executor'ы принимают текущее состояние». Убирает целый класс багов
   «граф и executors рассинхронены» и прямо реализует требование «заменить
   Executor цепочкой capability без изменения UI» — граф задан контрактами
   возможностей, а не отдельной картой.

2. **Введён ObjectState = тип + дешёвый набор признаков** (has-text,
   is-image-pdf, zip-of-images, has-url…). Качество первого экрана зависит от
   богатства состояния: если состояние = только MIME, пузырьки generic; если
   несёт лёгкие признаки — пузырьки умные.

3. **Первый экран <300 мс уточнён.** Первая отрисовка — только по нулевым
   сигналам (MIME, расширение, размер файла), без I/O. Более дорогое
   определение признаков (заглянуть внутрь PDF/ZIP) идёт async и *дополняет*
   пузырьки чуть позже (progressive disclosure). Иначе 200-МБ ZIP ломает
   обещание 300 мс.

4. **У результата Executor'а — явный канал ошибки/частичного результата.**
   `sealed ExecutorResult { Success / Failure(recoverable) / NeedsInput }`, плюс
   возможность «заглянуть/повторить шаг». Невидимая цепочка без обработки
   ошибок — ловушка отладки и доверия: OCR-промах даёт мусорный Excel, а юзер
   не видит, где сломалось.

5. **Жизненный цикл объекта: немедленный copy-in из Share Uri в приватный
   scratch-store.** Все шаги работают с собственной копией; по завершении флоу —
   очистка. Чинит классический Android-грабли: временный read-permission grant
   на `content://` Uri привязан к жизни принимающей Activity, и наивная передача
   Uri в корутину/ViewModel падает при пересоздании Activity.

6. **«Никаких синглтонов» уточнено** как «никаких глобальных/статических
   синглтонов и `object`». App-scoped Hilt-биндинги (реестр, scratch-store,
   Gemini-клиент) легитимны — это DI-инстанс, а не паттерн Singleton.

---

## Принципы (без изменений)
Никаких списков функций, меню, настроек, чатов. Минимум текста. Весь UI
строится вокруг пузырьков действий. Путешествие по состояниям объекта, а не
по приложениям.

## MVP: поддерживаемые типы
`image/*`, `text/plain`, `application/pdf`, `application/zip`, `text/uri-list`.

---

## Слои
- **UI (Bubble UI)** — только отображение объекта и пузырьков, ноль
  бизнес-логики.
- **Domain (core)** — модель, состояния, контракты Executor'ов, вывод Flow
  Graph.
- **Executors** — по одному на действие, независимы, заменяемы.
- **Data** — object store (scratch, copy-in, cleanup), LLM-клиент.

---

## Модель данных — pure Kotlin, модуль `:core:model` (без Android API)
- `PointObject(id, mime, uri /* в scratch */, state: ObjectState, metadata)`
- `ObjectState(kind: ObjectKind, features: Set<Feature>)` — kind по MIME;
  features — дёшево вычисляемые сигналы
- `Bubble(icon, title, executorId, expectedNextState)`
- `ResultObject(type, mime, uri, metadata)` — оборачивается в:
- `sealed ExecutorResult { Success(ResultObject); Failure(reason, recoverable: Boolean); NeedsInput(prompt) }`

---

## Контракты — модуль `:core:flow`

```kotlin
interface Executor {
    val id: ExecutorId
    fun accepts(state: ObjectState): Boolean        // декларация входа
    fun produces(state: ObjectState): ObjectState   // ожидаемый выход (рёбра графа)
    suspend fun execute(input: PointObject, amendment: String?): ExecutorResult // cancellable
}

interface ExecutorRegistry {                         // Hilt multibinding @IntoSet
    fun bubblesFor(state: ObjectState): List<Bubble> // executors, чьи accepts(state)==true
    fun byId(id: ExecutorId): Executor
}

interface ObjectStore {                              // scratch, copy-in из Share Uri, cleanup
    suspend fun ingest(uri: Uri): PointObject
    suspend fun put(result: ResultObject): PointObject
    suspend fun clear()
}

interface LlmClient {                                // Gemini за интерфейсом (fakeable в тестах)
    suspend fun run(obj: PointObject, prompt: String): ResultObject
}
```

Flow Graph как отдельная хранимая сущность отсутствует: `bubblesFor(state)` и
есть граф, выведенный из контрактов Executor'ов. Добавил Executor → граф
расширился, без правок отдельной карты.

---

## Первый экран и латентность
- Первая отрисовка ≤ 300 мс, только по нулевым сигналам (mime / extension /
  size) → базовый набор пузырьков.
- Async-обогащение (peek в PDF/ZIP, детект has-text) добавляет/уточняет
  пузырьки после, прогрессивно.
- LLM **не** на первом экране. Gemini вызывается только после выбора действия,
  внутри `AiExecutor`.

---

## Навигация — стек состояний
- Флоу = стек `FlowFrame(state, object, bubbles)` в одной ViewModel; Compose
  рендерит верхний фрейм.
- Системный Back = pop фрейма; выход из приложения только при пустом стеке.
- Process death в середине флоу — для MVP допустимо потерять флоу; обязательна
  очистка scratch-store.

---

## AI Executor (аварийный универсальный)
1. авто-системный промпт по типу объекта;
2. добавить инфо об объекте;
3. приложить файл (учесть лимиты Gemini: inline data vs Files API по
   размеру/MIME);
4. дать юзеру дописать запрос;
5. отправить запрос модели;
6. получить результат;
7. **материализовать вывод модели в ResultObject** (напр. markdown-ответ → `.md`
   файл в scratch) и вернуть в Flow Graph.

Юзер никогда не видит чата — только новый объект. Ключ Gemini — из
`local.properties` через BuildConfig, не в коде и не в репозитории.

---

## Модули (Gradle) — рекомендация для MVP
- `:app` — DI-wiring, Share Activity, Compose host, навигация-стек
- `:core:model` — pure Kotlin модель *(выделить: важная граница, легко
  тестируется)*
- `:core:flow` — контракты Executor/Registry, вывод графа *(выделить)*
- `:core:ui` — Bubble UI компоненты, дизайн-система
- `:data` — ObjectStore + LlmClient (Gemini)
- `:executors` — все Executor'ы пакетами внутри одного модуля на MVP

Компромисс: полный per-executor multi-module даёт максимум изоляции
(требование «независимо заменяемый»), но плодит Gradle-оверхед и замедляет
сборку. Для MVP пакеты внутри `:executors` + строгие интерфейсы дают ту же
заменяемость дешевле; дробить на модули позже — тривиально. **Решение за тобой.**

---

## Тестирование
Каждый side-effect (file IO, network, Android framework, PDF-рендер) — за
интерфейсом, в unit-тестах подставляются fakes. Pure-модули (`:core:model`,
`:core:flow`, чистая логика каждого Executor'а) покрываются напрямую. Это и есть
практический выхлоп принципа «max interfaces».

---

## Стек
Kotlin, Jetpack Compose, Material 3, MVVM, Hilt (multibinding), Coroutines +
Flow (structured concurrency, отменяемые Executor'ы), Activity Result API,
Android Share Intent. minSdk — актуальный стабильный уровень.

---

## Executor'ы MVP (стартовый набор)
- `ShareExecutor` — любой объект → системный Share
- `SaveExecutor` — → хранилище
- `PdfExecutor` — image/text → PDF; PDF → извлечение текста
- `ImageExecutor` — конвертация/сжатие image
- `ZipExecutor` — распаковка / архивирование
- `TranslateExecutor` — text/pdf → перевод
- `AiExecutor` — аварийный, Gemini

---

## Workflow (сохранён из v0.1)
Не писать код сразу. Сначала подготовь: структуру проекта, диаграмму модулей,
все интерфейсы, модель данных, вывод Flow Graph, жизненный цикл объекта, Bubble
UI, последовательность экранов, список Executor'ов. Затем — моё подтверждение,
затем реализация. Видишь лучшее решение или архитектурную проблему — предложи
с компромиссами до кода.

---

## Открытые решения (нужен твой выбор до старта)
1. Flow Graph выводить из Executor-контрактов (реком.) или оставить отдельной
   таблицей переходов?
2. Модули: единый `:executors` на MVP (реком.) или per-executor сразу?
3. Process death в середине флоу: терять флоу (реком. для MVP) или персистить
   стек?
4. AiExecutor: какие типы вывода материализуем в v0.1 (минимум — text/markdown
   → файл)?

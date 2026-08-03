# Релей во все стороны и видимая связь — план работ

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Телефон и ПК работают друг с другом из любых сетей — включая принтер и сборку PDF на компьютере, — и обе стороны честно показывают, есть ли связь.

**Architecture:** Двусторонний запрос-ответ поверх релея **уже работает** — для общего буфера (`RelayPcClipboardSync` ↔ `RelayClipPoller`): телефон кладёт запрос в ящик «на ПК», компьютер отвечает в ящик «на телефон», ответ узнаётся по `reqId`. Этот приём обобщается в `RelayRpc`, и на него переводятся остальные операции, которые сегодня работают только по локальной сети.

**Tech Stack:** Kotlin, Hilt, JUnit4; Compose (телефон), Compose Desktop (ПК).

## Зачем это вообще (факты, а не предположения)

- `LanThenRelayTransport` пускает через релей **только `send`**. `pair`, `fetchCaps`, `fetchOutbox`, `downloadOutboxFile`, `ackOutbox`, `pushPhoneCaps` — LAN-only.
- У владельца на роутере **изоляция клиентов**: телефон видит шлюз за 16 мс, а компьютер — не видит вовсе (100% потерь, проверено с обеих сторон 03.08.2026). То есть локальной сети между его устройствами нет **никогда**, даже когда оба дома.
- Итог: спариться нельзя, забрать сделанное на ПК нельзя, узнать возможности ПК нельзя. Работает ровно одно направление — отправка объекта на ПК. И ни одна сторона об этом не говорит.

## Global Constraints

- **Слепота релея не нарушается.** Он и дальше не видит ни токена, ни содержимого: всё едет запечатанным (`RelayCrypto.seal`), ящики адресуются производной от токена.
- Ответ узнаётся по `reqId`; чужие и протухшие ответы пропускаются, а не принимаются на веру (урок #272).
- Размер блоба гейтится **до** сети (`MAX_RELAY_BLOB`): релей отвечает 413 по `Content-Length`, и без гейта «слишком большой» превращается в ложное «недоступен».
- LAN остаётся первым: он быстрее и не зависит от облака. Релей — фолбэк, а не замена.
- Отказ говорит словами и на телефоне, и на ПК; «нет связи» не выглядит как «ничего не произошло».
- Backtick-имена тестов без `:` и `;`.

---

### Task 1: `RelayRpc` — запрос-ответ поверх ящиков

**Files:**
- Create: `data/src/main/kotlin/com/point/data/RelayRpc.kt`
- Test: `data/src/test/kotlin/com/point/data/RelayRpcTest.kt`

**Interfaces:**
- Produces: `class RelayRpc(appSecret, waitSeconds)` с `suspend fun ask(pairing: PcPairing, kind: String, meta: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0)): RpcReply?`; `data class RpcReply(val meta: Map<String, String>, val body: ByteArray)`; константы видов запросов `RpcKind.CAPS`, `OUTBOX`, `FETCH`, `ACK`, `PHONE_CAPS`.

Логика переносится из `RelayPcClipboardSync.pullSealed` дословно по смыслу: дренаж ящика телефона → отправка запроса с `reqId` → ожидание своего ответа до дедлайна, чужое пропускается.

- [ ] **Step 1: Тест на чистую часть** — разбор ответа: свой `reqId` принимается, чужой пропускается, отсутствующий (старый ПК) принимается ради совместимости.
- [ ] **Step 2: Реализация** `RelayRpc` поверх `RelayCrypto` + `encodePcFrame`/`decodePcFrame`, вид запроса едет в мете как `rpc.kind`, идентификатор — `rpc.id`.
- [ ] **Step 3: Тест зелёный, коммит.**

---

### Task 2: ПК отвечает на запросы из релея

**Files:**
- Create: `desktop/src/main/kotlin/com/point/desktop/RelayRequestPoller.kt`
- Modify: `desktop/src/main/kotlin/com/point/desktop/Main.kt`

Сейчас у ПК два поллера: объектов (`RelayPoller`) и буфера (`RelayClipPoller`). Третий слушает тот же ящик «на ПК» и отвечает на запросы по `rpc.kind`:

| запрос | что отвечает ПК |
|---|---|
| `caps` | список объявляемых действий (тот же, что по LAN: открыть, копировать, печать, **сделать PDF**) |
| `outbox` | список того, что ждёт телефон |
| `fetch` | байты одного объекта из очереди |
| `ack` | подтверждение забора |
| `phone-caps` | принимает возможности телефона |

- [ ] **Step 1:** поллер и разбор `rpc.kind`.
- [ ] **Step 2:** ответы формируются тем же кодеком, что и LAN-ответы, — второй правды не заводим.
- [ ] **Step 3:** проверка на живом релее (сервер поднят), коммит.

---

### Task 3: Телефон ходит через релей всеми операциями

**Files:**
- Modify: `data/src/main/kotlin/com/point/data/RelayPcTransport.kt`
- Modify: `data/src/main/kotlin/com/point/data/LanThenRelayTransport.kt`
- Test: `data/src/test/kotlin/com/point/data/LanThenRelayTransportTest.kt`

- [ ] **Step 1: Тест** — каждый метод при `Unreachable` по LAN уходит в релей, а при живом LAN не уходит.
- [ ] **Step 2:** `RelayPcTransport` реализует `fetchCaps`/`fetchOutbox`/`downloadOutboxFile`/`ackOutbox`/`pushPhoneCaps` через `RelayRpc`.
- [ ] **Step 3:** `LanThenRelayTransport` перестаёт резать эти методы на LAN-only.
- [ ] **Step 4:** коммит.

**Что это сразу даёт:** принтер и сборка PDF на компьютере становятся доступны из любой сети — они и так работают как «действие ПК над присланным объектом», но телефон о них не знал (`fetchCaps` был LAN-only), а результат забрать не мог (`fetchOutbox`).

---

### Task 4: Пейринг без локальной сети

**Files:**
- Modify: `data/src/main/kotlin/com/point/data/RelayPcTransport.kt`, `app/src/main/kotlin/com/point/FlowViewModel.kt`

QR уже несёт всё нужное: адрес, порт, **токен** и релей. Значит связать устройства можно без обращения по локальной сети — подтверждение спрашивается у компьютера через релей (`rpc.kind = pair`), а при недоступности честно говорится, что компьютер не ответил.

- [ ] **Step 1: Тест** — пейринг из QR при мёртвом LAN уходит в релей.
- [ ] **Step 2:** реализация и ответ ПК на `pair`.
- [ ] **Step 3:** коммит.

---

### Task 5: Связь видно с обеих сторон (#412)

**Files:**
- Modify: `desktop/src/main/kotlin/com/point/desktop/DesktopState.kt`, `ui/DesktopApp.kt`
- Modify: `app/src/main/kotlin/com/point/FlowViewModel.kt`, экран ПК на телефоне
- Create: `core/flow/src/main/kotlin/com/point/core/flow/LinkState.kt`
- Test: `core/flow/src/test/kotlin/com/point/core/flow/LinkStateTest.kt`

Общий язык состояния — в `:core:flow`, чтобы обе стороны говорили одинаково: `LinkState { LAN, RELAY, SILENT }` плюс «когда последний раз слышали друг друга».

- [ ] **Step 1: Тест на чистое правило** — как из последнего контакта и его пути получается состояние; молчание дольше N минут — это `SILENT`, а не «наверное, всё хорошо».
- [ ] **Step 2: ПК** отмечает контакт при каждом запросе (LAN и релей) и показывает состояние в полосе окна.
- [ ] **Step 3: Телефон** показывает то же на экране компьютера.
- [ ] **Step 4:** коммит.

---

## Что этот план не делает

- **Не переписывает релей в сервер Point с Google-аккаунтом** — это следующий этап (#413), здесь чинится нынешний.
- **Не трогает изоляцию клиентов** на роутере владельца: это настройка его сети. План делает так, чтобы Point работал и при ней.

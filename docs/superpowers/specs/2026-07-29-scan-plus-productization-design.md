# Скан+ продуктизация (on-device) — design

**Дата:** 2026-07-29 · **Issue:** #200 (ветка «обработка документов в APK») · **Статус:** approved, → writing-plans

## Context / Goal

Оффлайн на ПК (Python: page_dewarp/UVDoc/OpenCV/python-docx) доказан рецепт превращения
фото плотного бланка/ведомости в «флэтбед»-скан: **выпрямление по линиям таблицы** +
**финишер «чисто-белый фон, живой цвет»**. Владелец: вся эта обработка должна работать
**в приложении** — в «Скан+» (и позже «Word+»). Эта спека — только **Скан+**.

Цель: переписать on-device `OpenCvScan.enhance` (путь «Скан+») так, чтобы он делал ОБЩИЙ
переносимый рецепт целиком (один срез): дюварп по линиям → финишер бел/цвет → апскейл.
Переносится всё на **чистом OpenCV** (питон-прототип это подтвердил); нейросеть UVDoc и
python-only page_dewarp — НЕ на устройстве.

**Решения владельца:** (1) весь рецепт одним срезом/PR; (2) рабочее разрешение Скан+ поднять
до ~2600px (сейчас всё режется до 1600, память #18) — детальность нужна для плотных рукописных
таблиц; принят риск OOM с митигацией.

## Что переносится, что нет

- **Переносится (общий рецепт):** детект линий таблицы → полиномное поле смещений → remap;
  баланс белого → маска чернил по локальному среднему → бленд к белому + boost насыщенности;
  inpaint ярких бликов; фолбэк на перспективу по углам, если линий мало.
- **НЕ переносится (image-specific хаки Акта, под один документ):** zone-wipe плёночной мути в
  верх/лев поле, детилт углового блока «Додаток», добел верхней кромки. Скан+ — общая фича, эти
  костыли в неё не идут (инвариант «не глотай ошибки»: честно фиксируем границу).
- **НЕ на устройстве:** page_dewarp, UVDoc (Python/нейросеть; TFLite-модель — потенциально позже).

## Архитектура

Всё в `:executors`. **Без новых Capability/Realizer и без изменений DI** — «Скан+» уже провязан
(`ScanPlusCapability` `@Binds @IntoSet`; `ScanPlusRealizer` через `@Provides @IntoSet` в companion,
KSP-AAR трюк — `CapabilityModule.kt:236`). Меняем только внутренности `OpenCvScan.enhance` +
разрешение декода в `ScanPlusRealizer`.

### Компоненты

**1. `DewarpField` — pure Kotlin (`:executors`, без Android/OpenCV), JVM-тестируемый.**
Рискованная математика — изолируем и тестируем.
- `data class Anchor(val x: Double, val y: Double, val v: Double)` — точка поля (в норм. коорд.) и её смещение.
- `fun fit(anchors: List<Anchor>): DoubleArray` — бивариантный полином степени 3 (10 членов:
  `1, x, y, x², xy, y², x³, x²y, xy², y³`), коэффициенты через **нормальные уравнения** `AᵀA c = Aᵀv`,
  решённые собственным Гауссом (10×10) — чтобы остаться pure (без `Core.solve`). `< 12` якорей → нули.
- `fun eval(coeffs: DoubleArray, xn: Double, yn: Double): Double` — значение поля в точке.
- Координаты нормируются к `[-1,1]` для устойчивости.

**2. `OpenCvScan` — native (on-device), новые/переписанные методы (все на `Mat`, release в finally):**
- `private fun detectRules(gray: Mat, axis, minSpanFrac, kernel): List<Rule>` — морфология OPEN длинным
  ядром (H: `(w/12, 1)`; V: `(1, h/25)`) → `connectedComponentsWithStats` → по компоненте с достаточным
  спаном усредняем координату поперёк по каждой вдоль-координате и фитим poly2 → полилиния.
- `private fun dewarpByRules(rgba: Mat, gray: Mat): Mat?` — детект H-правил (minSpan `0.30·w`) и
  V-правил (`0.12·h`); если H-правил `< MIN_RULES` (напр. 6) → `null`. Иначе: для каждого H-правила
  цель `T = mean(y)`, якорь `(x, T) → (y−T)` (поле `Dy`); аналогично `Dx` из V-правил; `DewarpField.fit`
  на каждое поле; строим `mapX = gx + Dx`, `mapY = gy + Dy` на рабочем разрешении → resize до полного /
  scale → `Imgproc.remap(rgba, mapX, mapY, INTER_CUBIC, BORDER_REPLICATE)`.
- `private fun whitenFinish(rgba: Mat): Mat` — финишер:
  1. inpaint бликов: маска `min(ch) > 236 && gray > 244`, dilate 5×5×2, `INPAINT_TELEA`;
  2. LAB, WB: `A -= median(A)−128`, `B -= median(B)−128`;
  3. `gray`,`S` из WB-версии; `local = blur(gray, 51×51)`; `ink = clip((local−12−gray)/26, 0,1)`;
     `col = clip((S−30)/30, 0,1)`; `content = GaussianBlur(max(ink,col), σ≈1)`;
  4. `srcv = unsharp(LAB2BGR(L·контраст (L−25)·1.12, A, B), 1.30/−0.30@σ1.2)`;
  5. `out = srcv·content + 255·(1−content)` (бумага→чисто-белый, контент сохранён);
  6. sat-boost: HSV `S ·= 1.7` (бумага S≈0 не трогается; печать/подпись синеют).
- `fun enhance(src: Bitmap): Bitmap` (переписан): `Mat` → детект правил →
  `dewarpByRules(rgba, gray)` **?:** `detectDocument(rgba)`(corner-warp, существующий фолбэк) →
  `whitenFinish` → `upscale` (существующий, к `UPSCALE_TARGET`) → `Bitmap`.
  Любой бросок внутри → пробрасывается; realizer завернёт в recoverable Failure (fallback-цепочки у
  Скан+ нет, но recoverable корректно сообщит ошибку, а не уронит).

**3. `ScanPlusRealizer` (`ScanPlusAction.kt:48`).** Единственное изменение: декодить
`Bitmaps.decodeUpright(input.uri.value, Bitmaps.SCAN_PLUS_MAX_PX)` вместо дефолтного 1600. Остальное
(JPEG 92, `store.newScratchFile("jpg")`, `ResultObject(IMAGE,"image/jpeg",…,{op:scan-plus})`) — без изменений.

**4. `Bitmaps` (`Bitmaps.kt:19`).** Новая `const val SCAN_PLUS_MAX_PX = 2600`. `PROCESS_MAX_PX = 1600`
для «Скан»/PDF/прочих — не трогаем.

## Поток данных

Share IMAGE → `ingest` scratch → «Скан+» bubble → `ScanPlusRealizer.perform` →
`Bitmaps.decodeUpright(path, 2600)` → `OpenCvScan.enhance` (детект→дюварп/фолбэк→финишер→апскейл) →
JPEG 92 → `newScratchFile` → `ResultObject(IMAGE)` → чейнится дальше («В Excel»/save/share).

## Память / производительность (риск + митигация)

`Bitmap` 2600×~3670×4 ≈ 38 МБ + несколько `Mat`; на Samsung A34 риск OOM.
- Тяжёлые шаги (морфология, `blur 51`, `connectedComponents`, детект) — на **рабочем** ~1600px
  (downscale, как `detectDocument` уже делает при `DETECT_MAX_PX=720` для детекта); поле смещений
  считаем на рабочем, `remap` и финиш — один раз на полном разрешении.
- Агрессивный `Mat.release()` (существующий scratch-паттерн), не держать промежуточные Bitmap.
- **Замер на эмуляторе** (SandboxActivity) и на устройстве; при OOM — константа-флаг откат к 1600.

## Тестирование

- **JVM `:executors:test`** (основное, TDD): `DewarpField` —
  синтетические наклонённые/выгнутые «линии» (наборы `Anchor`) → `fit` восстанавливает поле так, что
  остаточное отклонение выпрямленных точек < порога; `fit` детерминирован; `< 12` якорей → нули;
  плюс существующие `orderCorners`/`distance`. Нативный OpenCv-путь на JVM не гоняется (как сейчас).
- **Эмулятор (SandboxActivity)** — image-Sample → «Скан+» bubble → `perform`: визуальная проверка
  (белый фон, прямые линии таблицы, синяя печать) + профиль памяти на 2600px. Живые тесты — только
  на эмуляторе, не на телефоне владельца.
- **Guard «не глотай»:** при исключении в dewarp/finish `enhance` не падает — realizer вернёт
  `Failure(recoverable=true)` с понятным сообщением.

## Скоуп / YAGNI

- Один срез: полный общий рецепт в `OpenCvScan.enhance` + разрешение 2600 + `DewarpField` + тесты.
- НЕ сейчас: вынос финишера в общий с ч/б-«Скан» шаг; TFLite-дюварп; Word+ (отдельная спека);
  per-document полиш (углы/плёнка Акта).

## Верификация (приёмка)

`./gradlew :executors:test :core:flow:test assembleDebug` зелёные; `DewarpField`-тесты проходят;
на эмуляторе «Скан+» на фото-бланке даёт прямые линии + белый фон + цветную печать без OOM;
ветка `feat/200-scan-plus-ondevice` → PR `Closes #200`(частично) → squash-merge на зелёном CI →
запись в `DECISIONS.md`.

## Швы (file:line, из карты кода)

- Скан+ путь: `executors/.../ScanPlusAction.kt:48` · `OpenCvScan.kt:52` (`enhance`), `:107` (`detectDocument`), `:85` (`upscale`)
- Декод/разрешение: `executors/.../Bitmaps.kt:19,23` (`PROCESS_MAX_PX`, `decodeUpright`)
- KSP-AAR трюк (не менять, но соблюдать): `executors/.../di/CapabilityModule.kt:236`
- Контракты: `core/flow/Realizer.kt:14` · `core/model/ActionResult.kt:10` · `core/flow/ObjectStore.kt` (`newScratchFile`)
- Тест-паттерн: `executors/.../OpenCvScanTest.kt` (pure geometry на JVM) · `docs/TESTING.md` (SandboxActivity)

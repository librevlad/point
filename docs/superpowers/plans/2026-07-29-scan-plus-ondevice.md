# Скан+ on-device (rule-dewarp + white finisher) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite the on-device «Скан+» pipeline (`OpenCvScan.enhance`) to straighten a photographed ruled document by its table lines and finish it to a clean white-background colour scan.

**Architecture:** All changes in `:executors`. A new **pure-Kotlin** `DewarpField` (bivariate degree-3 least-squares displacement field) is JVM-unit-tested. `OpenCvScan` gets native OpenCV methods (`detectRules`, `dewarpByRules`, `whitenFinish`) that call `DewarpField` and rewire `enhance = detect→dewarp(or corner-warp fallback)→whitenFinish→upscale`. `ScanPlusRealizer` decodes at a new higher cap. **No new Capability/Realizer, no DI changes** — «Скан+» is already wired (`@Provides @IntoSet` companion, KSP-AAR trick).

**Tech Stack:** Kotlin, OpenCV 4.14.0 (`org.opencv.*`), Hilt/KSP, JUnit4 (`:executors` unit tests), Gradle 8.14.3.

## Global Constraints

- CLI Gradle needs the JBR JDK: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"` each shell.
- **Native OpenCV code is NOT JVM-unit-testable** (no `.so` on the JVM test classpath). Only *pure* Kotlin (no `org.opencv.core.Mat`/Android) is unit-tested — `DewarpField` here, exactly like `OpenCvScan.orderCorners`/`distance` today. Native methods are verified by `assembleDebug` (compiles) + emulator (SandboxActivity), never on the owner's phone.
- Single-class test run uses **`:executors:testDebugUnitTest --tests …`** — plain `:executors:test --tests` does NOT filter (known gotcha).
- Do **not** add a `@Binds @IntoSet` whose signature mentions an OpenCV-touching concrete type — it poisons the whole Hilt module (KSP can't resolve the native AAR). This plan adds no new realizer, so no DI edits; keep it that way.
- `Bitmaps.PROCESS_MAX_PX = 1600` stays unchanged for «Скан»/PDF/others; only «Скан+» uses the new `SCAN_PLUS_MAX_PX`.
- Branch `feat/200-scan-plus-ondevice`; squash-merge only on green CI; PR title = Conventional-Commit subject + `Closes #200` (partial). Commit trailers required:
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_01N8Df8mBs75tgnfXMLhPdin`.
- Reference recipe & exact constants: `docs/superpowers/specs/2026-07-29-scan-plus-productization-design.md` (mirrors the offline prototypes `grid_dewarp2.py` / `scan_finish.py`).

---

## File Structure

- **Create** `executors/src/main/kotlin/com/point/executors/DewarpField.kt` — pure-Kotlin bivariate degree-3 least-squares field (`Anchor`, `terms`, `eval`, `fit`). No Android/OpenCV. One responsibility: the risky math.
- **Create** `executors/src/test/kotlin/com/point/executors/DewarpFieldTest.kt` — JVM unit tests for `DewarpField`.
- **Modify** `executors/src/main/kotlin/com/point/executors/OpenCvScan.kt` — add native `detectRules`, `dewarpByRules`, `whitenFinish`; rewrite `enhance`. New constants.
- **Modify** `executors/src/main/kotlin/com/point/executors/Bitmaps.kt` — add `const val SCAN_PLUS_MAX_PX = 2600`.
- **Modify** `executors/src/main/kotlin/com/point/executors/ScanPlusAction.kt:48` — decode at `Bitmaps.SCAN_PLUS_MAX_PX`.
- **Modify** `docs/DECISIONS.md` — record the decision.

---

## Task 0: Branch

- [ ] **Step 1: Create the feature branch**

```bash
cd C:/point && git checkout -b feat/200-scan-plus-ondevice
```

---

## Task 1: `DewarpField` — pure-Kotlin least-squares displacement field (TDD)

**Files:**
- Create: `executors/src/main/kotlin/com/point/executors/DewarpField.kt`
- Test: `executors/src/test/kotlin/com/point/executors/DewarpFieldTest.kt`

**Interfaces:**
- Produces: `object DewarpField` with `data class Anchor(x: Double, y: Double, v: Double)`, `fun terms(xn: Double, yn: Double): DoubleArray` (10 elems), `fun eval(coeffs: DoubleArray, xn: Double, yn: Double): Double`, `fun fit(anchors: List<Anchor>): DoubleArray` (10 coeffs; all-zero if `< 12` anchors or singular). Coordinates are pre-normalized to `[-1,1]` by the caller.

- [ ] **Step 1: Write the failing test**

`executors/src/test/kotlin/com/point/executors/DewarpFieldTest.kt`:
```kotlin
package com.point.executors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DewarpFieldTest {
    @Test
    fun `fit recovers a degree-2 field within tolerance`() {
        fun truth(x: Double, y: Double) = 0.4 + 0.3 * x - 0.2 * y + 0.15 * x * y
        val anchors = buildList {
            for (gx in -5..5) for (gy in -5..5) {
                val xn = gx / 5.0; val yn = gy / 5.0
                add(DewarpField.Anchor(xn, yn, truth(xn, yn)))
            }
        }
        val c = DewarpField.fit(anchors)
        for ((x, y) in listOf(-0.7 to 0.3, 0.2 to -0.9, 0.0 to 0.0)) {
            assertEquals(truth(x, y), DewarpField.eval(c, x, y), 1e-6)
        }
    }

    @Test
    fun `fewer than 12 anchors yields the zero field`() {
        val c = DewarpField.fit(List(5) { DewarpField.Anchor(0.1 * it, 0.1 * it, 1.0) })
        assertTrue(c.all { it == 0.0 })
    }

    @Test
    fun `eval computes the polynomial basis`() {
        val c = DoubleArray(10).also { it[1] = 2.0; it[2] = 3.0 } // v = 2·xn + 3·yn
        assertEquals(2 * 0.5 + 3 * -0.25, DewarpField.eval(c, 0.5, -0.25), 1e-9)
    }
}
```

- [ ] **Step 2: Run the test — verify it FAILS**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
cd C:/point && ./gradlew :executors:testDebugUnitTest --tests "com.point.executors.DewarpFieldTest"
```
Expected: compile/failure — `DewarpField` unresolved.

- [ ] **Step 3: Implement `DewarpField`**

`executors/src/main/kotlin/com/point/executors/DewarpField.kt`:
```kotlin
package com.point.executors

import kotlin.math.abs

/**
 * Pure-Kotlin bivariate degree-3 displacement field, fit by least squares.
 * No Android/OpenCV — JVM-unit-tested (native OpenCV stays behind OpenCvScan).
 * Caller normalizes coordinates to [-1,1].
 */
object DewarpField {
    private const val TERMS = 10

    data class Anchor(val x: Double, val y: Double, val v: Double)

    /** Degree-3 bivariate monomial basis: 1, x, y, x², xy, y², x³, x²y, xy², y³. */
    fun terms(xn: Double, yn: Double): DoubleArray = doubleArrayOf(
        1.0, xn, yn, xn * xn, xn * yn, yn * yn,
        xn * xn * xn, xn * xn * yn, xn * yn * yn, yn * yn * yn,
    )

    fun eval(coeffs: DoubleArray, xn: Double, yn: Double): Double {
        val t = terms(xn, yn)
        var s = 0.0
        for (i in 0 until TERMS) s += coeffs[i] * t[i]
        return s
    }

    /** Solve normal equations (AᵀA)c = Aᵀv. Zero field if under-determined or singular. */
    fun fit(anchors: List<Anchor>): DoubleArray {
        if (anchors.size < 12) return DoubleArray(TERMS)
        val ata = Array(TERMS) { DoubleArray(TERMS) }
        val atv = DoubleArray(TERMS)
        for (a in anchors) {
            val t = terms(a.x, a.y)
            for (i in 0 until TERMS) {
                atv[i] += t[i] * a.v
                for (j in 0 until TERMS) ata[i][j] += t[i] * t[j]
            }
        }
        return solve(ata, atv) ?: DoubleArray(TERMS)
    }

    /** Gaussian elimination with partial pivoting; null if singular. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1).also { r -> for (j in 0 until n) r[j] = a[i][j]; r[n] = b[i] } }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            for (r in 0 until n) if (r != col) {
                val f = m[r][col] / m[col][col]
                for (c in col..n) m[r][c] -= f * m[col][c]
            }
        }
        return DoubleArray(n) { i -> m[i][n] / m[i][i] }
    }
}
```

- [ ] **Step 4: Run the test — verify it PASSES**

```bash
./gradlew :executors:testDebugUnitTest --tests "com.point.executors.DewarpFieldTest"
```
Expected: PASS (3 tests), pristine output.

- [ ] **Step 5: Commit**

```bash
git add executors/src/main/kotlin/com/point/executors/DewarpField.kt executors/src/test/kotlin/com/point/executors/DewarpFieldTest.kt
git commit  # subject: "feat: DewarpField — pure least-squares dewarp field (#200)" + required trailers
```

---

## Task 2: `OpenCvScan.detectRules` + `dewarpByRules` — rule-based geometry (native; build-verified)

**Files:**
- Modify: `executors/src/main/kotlin/com/point/executors/OpenCvScan.kt`

**Interfaces:**
- Consumes: `DewarpField.fit`, `DewarpField.eval` (Task 1); existing `detectDocument`, `warp`, private scratch pattern.
- Produces: `private fun dewarpByRules(rgba: Mat, scratch: MutableList<Mat>): Mat?` — returns the rule-straightened full-res image, or `null` when too few table rules (caller falls back to corner-warp).

**No unit test** (native OpenCV). Verified by `assembleDebug` here; behaviour on emulator in Task 5. Mirror the offline `grid_dewarp2.py` constants.

- [ ] **Step 1: Add constants + `Rule` model + `detectRules`**

Add to `OpenCvScan.kt` (near the other private helpers / constants at end):
```kotlin
private const val RULE_DETECT_PX = 1600.0   // работаем на этом long-side для детекта/поля
private const val MIN_H_RULES = 6           // меньше — дюварпа нет, фолбэк на углы
private const val POLY_TERMS = 10

/** Полилиния правила: пары (along, across) в координатах рабочей копии. */
private class Rule(val along: DoubleArray, val across: DoubleArray)

/**
 * Длинные прямые правила таблицы. axis=0 — горизонтальные (across=y от along=x),
 * axis=1 — вертикальные (across=x от along=y). Морфология OPEN длинным ядром → компоненты →
 * по каждой along-координате среднее across.
 */
private fun detectRules(gray: Mat, axis: Int, minSpanFrac: Double, kernel: Mat, scratch: MutableList<Mat>): List<Rule> {
    val w = gray.cols(); val h = gray.rows()
    val bin = Mat().also { scratch += it }
    Imgproc.adaptiveThreshold(gray, bin, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 25, 15.0)
    val op = Mat().also { scratch += it }
    Imgproc.morphologyEx(bin, op, Imgproc.MORPH_OPEN, kernel)
    val labels = Mat().also { scratch += it }
    val stats = Mat().also { scratch += it }
    val centroids = Mat().also { scratch += it }
    val n = Imgproc.connectedComponentsWithStats(op, labels, stats, centroids)
    val dim = if (axis == 0) w else h
    val rules = mutableListOf<Rule>()
    for (i in 1 until n) {
        val span = stats.get(i, if (axis == 0) Imgproc.CC_STAT_WIDTH else Imgproc.CC_STAT_HEIGHT)[0]
        if (span < minSpanFrac * dim) continue
        val mask = Mat().also { scratch += it }
        Core.compare(labels, Scalar(i.toDouble()), mask, Core.CMP_EQ)
        val pts = MatOfPoint().also { scratch += it }
        Core.findNonZero(mask, pts)                 // только пиксели этого правила
        // среднее across по каждой along-координате
        val sum = HashMap<Int, DoubleArray>()       // along -> [sumAcross, count]
        for (p in pts.toArray()) {
            val along = (if (axis == 0) p.x else p.y).toInt()
            val across = if (axis == 0) p.y else p.x
            val acc = sum.getOrPut(along) { DoubleArray(2) }
            acc[0] += across; acc[1] += 1.0
        }
        if (sum.size < 8) continue
        val keys = sum.keys.sorted()
        rules += Rule(
            DoubleArray(keys.size) { keys[it].toDouble() },
            DoubleArray(keys.size) { sum[keys[it]]!!.let { a -> a[0] / a[1] } },
        )
    }
    return rules
}
```

- [ ] **Step 2: Add `dewarpByRules`**

```kotlin
/**
 * Выпрямление по линиям таблицы. Каждое горизонтальное правило → на свою среднюю высоту
 * (поле Dy), вертикальные → на средний x (поле Dx); поля через DewarpField. remap полного
 * кадра. null, если горизонтальных правил < MIN_H_RULES → фолбэк на corner-warp.
 */
private fun dewarpByRules(rgba: Mat, scratch: MutableList<Mat>): Mat? {
    val longSide = maxOf(rgba.rows(), rgba.cols()).toDouble()
    val s = if (longSide > RULE_DETECT_PX) RULE_DETECT_PX / longSide else 1.0
    val small = Mat().also { scratch += it }
    if (s < 1.0) Imgproc.resize(rgba, small, Size(rgba.cols() * s, rgba.rows() * s)) else rgba.copyTo(small)
    val w = small.cols(); val h = small.rows()
    val gray = Mat().also { scratch += it }
    Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)

    val hk = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size((w / 12).toDouble(), 1.0)).also { scratch += it }
    val vk = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, (h / 25).toDouble())).also { scratch += it }
    val hRules = detectRules(gray, 0, 0.30, hk, scratch)
    val vRules = detectRules(gray, 1, 0.12, vk, scratch)
    if (hRules.size < MIN_H_RULES) return null

    // якоря: в выходной точке (x, T) source_y = y(x); T = средняя высота правила. Норм. к [-1,1].
    fun nx(x: Double) = x / w * 2 - 1
    fun ny(y: Double) = y / h * 2 - 1
    val ay = mutableListOf<DewarpField.Anchor>()
    for (r in hRules) {
        val t = r.across.average()
        for (k in r.along.indices) ay += DewarpField.Anchor(nx(r.along[k]), ny(t), r.across[k] - t)
    }
    val ax = mutableListOf<DewarpField.Anchor>()
    for (r in vRules) {
        val srcx = r.across.average()
        for (k in r.along.indices) ax += DewarpField.Anchor(nx(srcx), ny(r.along[k]), r.across[k] - srcx)
    }
    val cy = DewarpField.fit(ay)
    val cx = DewarpField.fit(ax)

    // remap-карты на рабочем разрешении, апскейл до полного, /s (координаты в full-res)
    val mapXs = Mat(h, w, CvType.CV_32F).also { scratch += it }
    val mapYs = Mat(h, w, CvType.CV_32F).also { scratch += it }
    val rowX = FloatArray(w); val rowY = FloatArray(w)
    for (yy in 0 until h) {
        val yn = ny(yy.toDouble())
        for (xx in 0 until w) {
            val xn = nx(xx.toDouble())
            rowX[xx] = (xx + DewarpField.eval(cx, xn, yn)).toFloat()
            rowY[xx] = (yy + DewarpField.eval(cy, xn, yn)).toFloat()
        }
        mapXs.put(yy, 0, rowX); mapYs.put(yy, 0, rowY)
    }
    val mapX = Mat().also { scratch += it }
    val mapY = Mat().also { scratch += it }
    Imgproc.resize(mapXs, mapX, Size(rgba.cols().toDouble(), rgba.rows().toDouble()))
    Imgproc.resize(mapYs, mapY, Size(rgba.cols().toDouble(), rgba.rows().toDouble()))
    Core.multiply(mapX, Scalar(1.0 / s), mapX)
    Core.multiply(mapY, Scalar(1.0 / s), mapY)
    val out = Mat().also { scratch += it }
    Imgproc.remap(rgba, out, mapX, mapY, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
    return out
}
```
(Imports to ensure at top of `OpenCvScan.kt`: `org.opencv.core.Core`, `Scalar`, `MatOfPoint`, `CvType`, `Size` — most already present.)

- [ ] **Step 3: Verify it compiles**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
cd C:/point && ./gradlew :executors:assembleDebug
```
Expected: `BUILD SUCCESSFUL`. (No behaviour test — native; validated on emulator in Task 5.)

- [ ] **Step 4: Commit**

```bash
git add executors/src/main/kotlin/com/point/executors/OpenCvScan.kt
git commit  # "feat: OpenCvScan rule-detect + polynomial dewarp (#200)" + trailers
```

---

## Task 3: `OpenCvScan.whitenFinish` — white-background colour finisher (native; build-verified)

**Files:**
- Modify: `executors/src/main/kotlin/com/point/executors/OpenCvScan.kt`

**Interfaces:**
- Produces: `private fun whitenFinish(rgba: Mat, scratch: MutableList<Mat>): Mat` — WB → ink-mask (local-mean) → blend paper to pure white, keep dark text + boosted colour.

Mirror offline `scan_finish.py`. Median of a channel = histogram walk (reuse the `medianBrightness` pattern already in `OpenCvScan.kt:169`; generalize it to take any single-channel `Mat`).

- [ ] **Step 1: Implement `whitenFinish`**

```kotlin
/** #200 финишер: чисто-белый фон, живой цвет. Всё OpenCV. */
private fun whitenFinish(rgba: Mat, scratch: MutableList<Mat>): Mat {
    // 1) inpaint ярких бликов
    val bgr = Mat().also { scratch += it }
    Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
    val gray0 = Mat().also { scratch += it }
    Imgproc.cvtColor(bgr, gray0, Imgproc.COLOR_BGR2GRAY)
    val chans = ArrayList<Mat>().also { Core.split(bgr, it); scratch += it }
    val minCh = Mat().also { scratch += it }
    Core.min(chans[0], chans[1], minCh); Core.min(minCh, chans[2], minCh)
    val glare = Mat().also { scratch += it }
    Core.inRange(minCh, Scalar(237.0), Scalar(255.0), glare)
    val glareG = Mat().also { scratch += it }
    Imgproc.threshold(gray0, glareG, 244.0, 255.0, Imgproc.THRESH_BINARY)
    Core.bitwise_and(glare, glareG, glare)
    Imgproc.dilate(glare, glare, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)).also { scratch += it }, Point(-1.0, -1.0), 2)
    if (Core.countNonZero(glare) > 0) Photo.inpaint(bgr, glare, bgr, 5.0, Photo.INPAINT_TELEA)

    // 2) баланс белого (median a/b → 128)
    val lab = Mat().also { scratch += it }
    Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
    val lc = ArrayList<Mat>().also { Core.split(lab, it); scratch += it }
    Core.add(lc[1], Scalar(128.0 - medianOf(lc[1])), lc[1])
    Core.add(lc[2], Scalar(128.0 - medianOf(lc[2])), lc[2])
    Core.merge(lc, lab)
    val wb = Mat().also { scratch += it }
    Imgproc.cvtColor(lab, wb, Imgproc.COLOR_Lab2BGR)

    // 3) маска контента: ink по локальному среднему + цвет по насыщенности
    val gray = Mat().also { scratch += it }
    Imgproc.cvtColor(wb, gray, Imgproc.COLOR_BGR2GRAY)
    val grayF = Mat().also { scratch += it }; gray.convertTo(grayF, CvType.CV_32F)
    val local = Mat().also { scratch += it }
    Imgproc.blur(grayF, local, Size(51.0, 51.0))
    val ink = Mat().also { scratch += it }                         // clip((local-12-gray)/26,0,1)
    Core.subtract(local, grayF, ink); Core.subtract(ink, Scalar(12.0), ink)
    Core.multiply(ink, Scalar(1.0 / 26.0), ink)
    val hsv = Mat().also { scratch += it }
    Imgproc.cvtColor(wb, hsv, Imgproc.COLOR_BGR2HSV)
    val sc = ArrayList<Mat>().also { Core.split(hsv, it); scratch += it }
    val col = Mat().also { scratch += it }                         // clip((S-30)/30,0,1)
    sc[1].convertTo(col, CvType.CV_32F); Core.subtract(col, Scalar(30.0), col)
    Core.multiply(col, Scalar(1.0 / 30.0), col)
    val content = Mat().also { scratch += it }
    Core.max(ink, col, content)
    Imgproc.GaussianBlur(content, content, Size(0.0, 0.0), 1.0)
    Core.min(content, Scalar(1.0), content); Core.max(content, Scalar(0.0), content)

    // 4) srcv = контраст чернил + unsharp
    val srcv = Mat().also { scratch += it }
    wb.convertTo(srcv, -1, 1.12, -25.0 * 1.12)                     // (px-25)*1.12
    val blur = Mat().also { scratch += it }
    Imgproc.GaussianBlur(srcv, blur, Size(0.0, 0.0), 1.2)
    Core.addWeighted(srcv, 1.30, blur, -0.30, 0.0, srcv)

    // 5) бленд: бумага → белый, контент → srcv (3 канала)
    val out = Mat().also { scratch += it }
    val srcvF = Mat().also { scratch += it }; srcv.convertTo(srcvF, CvType.CV_32F)
    val c3 = Mat().also { scratch += it }
    Imgproc.cvtColor(content, c3, Imgproc.COLOR_GRAY2BGR)          // бродкаст маски в 3 канала
    val paper = Mat().also { scratch += it }
    Core.subtract(Mat.ones(c3.size(), c3.type()).also { scratch += it }, c3, paper)
    Core.multiply(srcvF, c3, srcvF)
    Core.multiply(paper, Scalar(255.0), paper)
    Core.add(srcvF, paper, srcvF)
    srcvF.convertTo(out, CvType.CV_8U)

    // 6) sat-boost печати/подписей (бумага S≈0 не трогается)
    Imgproc.cvtColor(out, hsv, Imgproc.COLOR_BGR2HSV)
    Core.split(hsv, sc)
    Core.multiply(sc[1], Scalar(1.7), sc[1])
    Core.merge(sc, hsv)
    Imgproc.cvtColor(hsv, out, Imgproc.COLOR_HSV2BGR)

    val outRgba = Mat().also { scratch += it }
    Imgproc.cvtColor(out, outRgba, Imgproc.COLOR_BGR2RGBA)
    return outRgba
}

/** Медиана одноканального 8U Mat через 256-бинную гистограмму (как medianBrightness). */
private fun medianOf(chan: Mat): Double {
    val hist = Mat()
    Imgproc.calcHist(listOf(chan), MatOfInt(0), Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
    val total = chan.total()
    var cum = 0.0
    for (b in 0 until 256) { cum += hist.get(b, 0)[0]; if (cum >= total / 2.0) return b.toDouble() }
    return 128.0
}
```
(Imports to ensure: `org.opencv.photo.Photo`, `org.opencv.core.MatOfInt`, `MatOfFloat`, `Point`.)

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :executors:assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add executors/src/main/kotlin/com/point/executors/OpenCvScan.kt
git commit  # "feat: OpenCvScan white-background colour finisher (#200)" + trailers
```

---

## Task 4: Rewire `enhance` + higher decode resolution (native; build-verified)

**Files:**
- Modify: `executors/src/main/kotlin/com/point/executors/OpenCvScan.kt:52` (`enhance`)
- Modify: `executors/src/main/kotlin/com/point/executors/Bitmaps.kt:19`
- Modify: `executors/src/main/kotlin/com/point/executors/ScanPlusAction.kt:48`

**Interfaces:**
- Consumes: `dewarpByRules` (Task 2), `whitenFinish` (Task 3), existing `detectDocument`/`upscale`.

- [ ] **Step 1: Rewrite `enhance`**

Replace the body of `fun enhance(src: Bitmap): Bitmap` (`OpenCvScan.kt:52`) with:
```kotlin
val rgba = Mat()
Utils.bitmapToMat(src, rgba)
val scratch = mutableListOf(rgba)
try {
    val straight = dewarpByRules(rgba, scratch)     // линии таблицы → полиномный дюварп
        ?: detectDocument(rgba, scratch)            // мало линий → перспектива по углам
        ?: rgba                                     // нет и углов → как есть
    val finished = whitenFinish(straight, scratch)  // чисто-белый фон + живой цвет
    val scaled = upscale(finished, scratch)         // к UPSCALE_TARGET
    val out = Bitmap.createBitmap(scaled.cols(), scaled.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(scaled, out)
    return out
} finally {
    scratch.forEach { it.release() }
}
```

- [ ] **Step 2: Add the decode-resolution constant**

In `Bitmaps.kt`, next to `PROCESS_MAX_PX` (`:19`):
```kotlin
/** «Скан+» декодит крупнее ради детальности плотных рукописных таблиц (#200). Память: см. риск в спеке. */
const val SCAN_PLUS_MAX_PX = 2600
```

- [ ] **Step 3: Decode «Скан+» at the higher cap**

In `ScanPlusAction.kt:48` (inside `ScanPlusRealizer.perform`), change the decode line:
```kotlin
val src = Bitmaps.decodeUpright(input.uri.value, Bitmaps.SCAN_PLUS_MAX_PX)
    ?: error("Не удалось прочитать изображение")
```

- [ ] **Step 4: Full build + all unit tests green**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
cd C:/point && ./gradlew :executors:test assembleDebug
```
Expected: `BUILD SUCCESSFUL`; `DewarpFieldTest` + existing tests pass.

- [ ] **Step 5: Commit**

```bash
git add executors/src/main/kotlin/com/point/executors/OpenCvScan.kt executors/src/main/kotlin/com/point/executors/Bitmaps.kt executors/src/main/kotlin/com/point/executors/ScanPlusAction.kt
git commit  # "feat: Скан+ pipeline = rule-dewarp → white finisher @2600 (#200)" + trailers
```

---

## Task 5: Emulator verification, DECISIONS.md, PR

**Files:**
- Modify: `docs/DECISIONS.md`

- [ ] **Step 1: Emulator smoke (SandboxActivity)**

Install to the running **emulator** (never the owner's phone): `./gradlew :app:installDebug`. In «Point Sandbox», feed the image Sample → tap «Скан+». Confirm: white background, straightened table lines, blue stamp keeps colour, no crash/OOM (watch `adb logcat` for `OutOfMemoryError`). If OOM: lower `SCAN_PLUS_MAX_PX` (e.g. 2200) and re-check; record the working value.

- [ ] **Step 2: Record the decision**

Append to `docs/DECISIONS.md` a short entry: on-device «Скан+» now does rule-based polynomial dewarp (`DewarpField`, JVM-tested) + white-background colour finisher, decode raised to `SCAN_PLUS_MAX_PX`; image-specific Акт polish and UVDoc/page_dewarp intentionally NOT ported; native path validated on emulator (`[[ksp-provides-for-native-aar-types]]` still governs — no new realizer).

- [ ] **Step 3: Commit + push + PR**

```bash
git add docs/DECISIONS.md && git commit  # "docs: record on-device Скан+ dewarp+finisher (#200)" + trailers
git push -u origin feat/200-scan-plus-ondevice
gh pr create --title "feat: on-device Скан+ rule-dewarp + white finisher (#200)" --body "…Closes #200 (partial: Скан+ path)…"
```
Expected: CI (`./gradlew test assembleDebug`) green. Squash-merge only when green; then delete the branch.

---

## Self-Review

**1. Spec coverage:** ① `DewarpField` pure+JVM-tests → Task 1 ✓. ② `detectRules`/`dewarpByRules` → Task 2 ✓; `whitenFinish` (WB+ink-mask+blend+sat-boost+glare-inpaint) → Task 3 ✓; `enhance` rewrite (dewarp→corner-fallback→finish→upscale) → Task 4 Step 1 ✓. ③ `SCAN_PLUS_MAX_PX=2600` + realizer decode → Task 4 Steps 2-3 ✓. No new Capability/DI ✓ (none added). Guard «не глотай» → covered by the existing `runCatching{…}.getOrElse{ Failure(recoverable=true) }` in `ScanPlusRealizer` (spec §Guard); `enhance` just propagates. OOM mitigation (work@1600, remap@full) → `dewarpByRules` computes the field at `RULE_DETECT_PX` and remaps full-res ✓; emulator OOM check → Task 5. Emulator-only testing ✓.

**2. Placeholder scan:** No TBD/TODO; every code step has full code; run commands concrete with expected output. Constants pinned (`RULE_DETECT_PX=1600`, `MIN_H_RULES=6`, decode `2600`, all finisher magic numbers). Median-of-channel spelled out (`medianOf`).

**3. Type consistency:** `DewarpField.Anchor(x,y,v)` / `fit(List<Anchor>):DoubleArray` / `eval(DoubleArray,Double,Double):Double` used identically in Task 1 and Task 2. `dewarpByRules(rgba, scratch): Mat?` and `whitenFinish(rgba, scratch): Mat` signatures match their calls in Task 4's `enhance`. `Bitmaps.SCAN_PLUS_MAX_PX` defined (Task 4.2) before use (Task 4.3). `medianOf` defined in Task 3 and used only there.

---

## Notes for the executor

- **YAGNI held:** no per-rule poly2 smoothing (the global degree-3 fit smooths noise); no finisher extraction into the B/W «Скан» path; no TFLite. If straightening is noisy on emulator, add per-rule poly2 as a follow-up, not now.
- **Not ported (by design):** the Акт-specific plastic zone-wipe, corner de-tilt, top-edge whiten — «Скан+» is the general recipe.
- Keep every native `Mat` in `scratch` and released in `finally` (existing pattern) — the new methods allocate many Mats.

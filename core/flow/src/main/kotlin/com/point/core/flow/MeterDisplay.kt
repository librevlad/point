package com.point.core.flow

import com.point.core.model.PointObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Поиск табло прибора на кадре — чистая часть (#262, счётчики 0 из 3 офлайн).
 *
 * **Зачем это вообще есть.** Второй замер корпуса назвал причину поимённо: движок на устройстве
 * не читает фото счётчика **вовсе** — слой атомов выходит символьным шумом (`И. | + | м | 4 | с`).
 * Дело не в схеме и не в правиле: Tesseract, запущенный на весь кадр, ищет строки текста, а на
 * фото прибора текста нет — есть барабан из восьми цифр под бликом, наклонённый на столько,
 * на сколько его повернули при установке. Отдать движку такой кадр целиком — значит получить шум.
 *
 * Поэтому кадру нужна подготовка: **найти табло, вырезать его, довернуть, увеличить и читать
 * только цифры**. Здесь живёт первая половина — «найти»; вырезать и увеличить умеет `:data`
 * (там Bitmap), читать — движок за контрактом [MeterReader].
 *
 * **Что считается табло.** Строка одинаковых знаков: 4–12 связных пятен примерно одной высоты и
 * ширины, стоящих на одной прямой с примерно равным шагом. Это ровно то, чем барабан отличается
 * от подписи на щитке: у слова буквы разной ширины, у барабана — цифры одного кегля в окошках.
 * Порода правила та же, что у [meterReadings] и [trackFacts]: дешёвое, офлайновое, ошибающееся —
 * поэтому оно **предлагает места, а не решает**, где табло.
 *
 * **Насколько ошибающееся — измерено.** Прогон по всем 23 кадрам корпуса: место находится на 22
 * из них (логотип на квитанции, строка письма, ряд дат в ведомости, гравий), а [score] документов
 * доходит до 0,196 против 0,178 у лучшего из трёх настоящих счётчиков — порога, отделяющего
 * прибор от документа, не существует. Судить прочитанным тоже нечем: движку в этом пути разрешены
 * только цифры, поэтому на строке букв он выдаёт цифры. Отсюда правило пользования: **эта функция
 * работает после того, как человек сказал «здесь прибор»**, и не может стоять там, где её ответ
 * подменяет чужой (#262 — чтение показания вынесено в отдельный тап, разбор в `DECISIONS.md`).
 *
 * **Почему кандидатов несколько.** Ранжирование заведомо неидеально: на кадре 17 корпуса рядом с
 * барабаном стоит «СО-ЭА09» — та же строка одинаковых знаков. Требовать от геометрии безошибочного
 * первого места значило бы поставить весь путь на догадку. Дешевле прочитать 2–3 верхних места
 * цифровым словарём (каждое — крошечная картинка) и оставить те, где цифры собрались.
 *
 * **Чего эта функция не делает.** Не выпрямляет перспективу, не борется с бликом и не читает
 * семисегментные табло со светодиодами (там знаки не связны — сегменты рассыпаются на отдельные
 * пятна). Всё это названо вслух, потому что «не нашли» здесь — законный и частый ответ, и он
 * обязан выглядеть как отказ, а не как пустота: см. [MeterReadout.candidates].
 */
class GrayFrame(
    val width: Int,
    val height: Int,
    /** Яркости 0..255 построчно, `width * height` штук. */
    val luma: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "frame must be non-empty, was ${width}x$height" }
        require(luma.size == width * height) { "luma must be width*height = ${width * height}, was ${luma.size}" }
    }

    fun at(x: Int, y: Int): Int = luma[y * width + x]

    val longEdge: Int get() = max(width, height)
}

/**
 * Поворот кадра на произвольный угол — вокруг центра, с переносом в положительную четверть.
 *
 * **Не [FrameTransform].** Тот знает ровно четыре угла (EXIF) и служит обратной дорогой в сырой
 * файл. Здесь угол любой: барабан водомера на кадре 09 корпуса стоит почти вертикально, потому что
 * прибор так вкручен в трубу, и никакой EXIF об этом не знает. Два разных преобразования с одним
 * именем однажды сложили бы углы дважды — поэтому имя другое.
 *
 * Соглашение — **то же, что у `Bitmap.createBitmap(src, …, Matrix().postRotate(deg), true)`**:
 * положительный угол крутит по часовой стрелке в экранных координатах (y вниз), результат
 * прижимается к началу координат. Совпадение обязательно: рамку считает чистый код, а режет её
 * Android — разъехавшиеся соглашения дали бы кроп из чужого места кадра.
 */
data class FrameRotation(val degrees: Int, val width: Int, val height: Int) {

    private val radians = degrees * PI / 180.0
    private val cos = cos(radians)
    private val sin = sin(radians)

    val rotatedWidth: Int = (abs(width * cos) + abs(height * sin)).roundToInt().coerceAtLeast(1)
    val rotatedHeight: Int = (abs(width * sin) + abs(height * cos)).roundToInt().coerceAtLeast(1)

    /** Место точки исходного кадра в повёрнутой копии. */
    fun toRotatedX(x: Float, y: Float): Float =
        ((x - width / 2.0) * cos - (y - height / 2.0) * sin + rotatedWidth / 2.0).toFloat()

    /** @see toRotatedX */
    fun toRotatedY(x: Float, y: Float): Float =
        ((x - width / 2.0) * sin + (y - height / 2.0) * cos + rotatedHeight / 2.0).toFloat()

    /** Место точки повёрнутой копии в исходном кадре — зеркало [toRotatedX]. */
    fun fromRotatedX(x: Float, y: Float): Float =
        ((x - rotatedWidth / 2.0) * cos + (y - rotatedHeight / 2.0) * sin + width / 2.0).toFloat()

    /** @see fromRotatedX */
    fun fromRotatedY(x: Float, y: Float): Float =
        (-(x - rotatedWidth / 2.0) * sin + (y - rotatedHeight / 2.0) * cos + height / 2.0).toFloat()

    /**
     * Наименьший прямоугольник исходного кадра, накрывающий [box] повёрнутой копии.
     *
     * Наклонная рамка в прямых координатах — всегда с запасом: обратно возвращается описанный
     * прямоугольник, а не тот же четырёхугольник. Это не потеря точности, а честность формы —
     * прямоугольник со сторонами по осям наклонную полосу иначе не опишет.
     */
    fun fromRotated(box: Box): Box {
        val xs = floatArrayOf(
            fromRotatedX(box.left, box.top), fromRotatedX(box.right, box.top),
            fromRotatedX(box.right, box.bottom), fromRotatedX(box.left, box.bottom),
        )
        val ys = floatArrayOf(
            fromRotatedY(box.left, box.top), fromRotatedY(box.right, box.top),
            fromRotatedY(box.right, box.bottom), fromRotatedY(box.left, box.bottom),
        )
        return Box(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /** Наименьший прямоугольник повёрнутой копии, накрывающий [box] исходного кадра. */
    fun toRotated(box: Box): Box {
        val xs = floatArrayOf(
            toRotatedX(box.left, box.top), toRotatedX(box.right, box.top),
            toRotatedX(box.right, box.bottom), toRotatedX(box.left, box.bottom),
        )
        val ys = floatArrayOf(
            toRotatedY(box.left, box.top), toRotatedY(box.right, box.top),
            toRotatedY(box.right, box.bottom), toRotatedY(box.left, box.bottom),
        )
        return Box(xs.min(), ys.min(), xs.max(), ys.max())
    }
}

/**
 * Место, предложенное как табло: рамка в **повёрнутой** копии кадра, где строка знаков
 * горизонтальна.
 *
 * Рамка живёт именно в повёрнутой системе, а не в сырой, потому что режут по ней тоже повёрнутую
 * копию: вырезанный из сырого кадра наклонный барабан пришлось бы крутить второй раз, и движок
 * получил бы знаки, дважды размазанные интерполяцией.
 *
 * @param angleDegrees на сколько повернуть кадр по часовой стрелке, чтобы строка легла ровно.
 * @param region рамка знаков (без полей) в координатах кадра, повёрнутого на [angleDegrees].
 * @param digits сколько знаков в строке — не «сколько цифр в показании»: часть барабана могла
 *   слипнуться с рамкой окошка, а часть — рассыпаться под бликом.
 * @param digitHeight средняя высота знака в пикселях кадра, на котором искали.
 * @param darkDigits знаки темнее фона (щиток электросчётчика) против светлее (барабан водомера) —
 *   от этого зависит, инвертировать ли вырезанный кусок перед чтением.
 * @param score мера правдоподобия, сравнимая **только внутри одного кадра**: чужой кадр даёт
 *   другой масштаб и другие пятна (тот же принцип, что у [Atom.confidence]).
 * @param frame поворот рабочего кадра, в системе которого посчитана [region] — по нему `:data`
 *   переводит рамку в масштаб полноразмерного снимка.
 */
data class MeterDisplayCandidate(
    val angleDegrees: Int,
    val region: Box,
    val digits: Int,
    val digitHeight: Float,
    val darkDigits: Boolean,
    val score: Float,
    val frame: FrameRotation,
)

/**
 * Длинная сторона рабочей копии, на которой ищется табло.
 *
 * Число измерено, а не выбрано: на кадрах 09/15/17 корпуса поиск прогнан при 448, 512, 640 и 800.
 * На 800 табло кадра 17 находится целиком и первым местом, на 640 — на треть короче (крайние
 * разряды теряются), на 448 барабан кадра 15 не собирается вовсе. Мельче — цифры сливаются с
 * рамкой окошка ещё до разбора на знаки; крупнее — растёт цена прохода, а вместе с ней и число
 * мелких строк щитка, которые лезут в кандидаты.
 */
const val METER_WORK_PX = 800

/** Сколько мест максимум отдавать читателю: каждое стоит отдельного прохода движка. */
const val METER_MAX_CANDIDATES = 3

/** Шаг перебора наклона, в градусах. */
private const val ANGLE_STEP = 5

/** Ниже — не знак, а крапина на щитке (в долях длинной стороны кадра). */
private const val MIN_DIGIT_SHARE = 0.012f

/** Выше — не цифра табло, а половина кадра (в долях длинной стороны). */
private const val MAX_DIGIT_SHARE = 0.12f

/** Меньше — не строка табло, а случайное совпадение двух пятен. */
private const val MIN_DIGITS = 4

/** Больше — строка текста, а не барабан: девятизначных счётчиков в природе хватает, двенадцати — нет. */
private const val MAX_DIGITS = 12

/** Разброс высот/ширин/шагов, выше которого строка — слово, а не барабан. */
private const val MAX_HEIGHT_SPREAD = 0.30f
private const val MAX_WIDTH_SPREAD = 0.40f
private const val MAX_GAP_SPREAD = 0.45f

/** Шаг между знаками в ширинах знака: у барабана окошки шире цифры, у слипшегося текста — уже. */
private const val MIN_PITCH = 0.9f
private const val MAX_PITCH = 4.0f

/** Строка короче этого (в высотах знака) — не табло, а пара пятен рядом. */
private const val MIN_SPAN_HEIGHTS = 2.5f

/** Насколько два места должны пересечься, чтобы считаться одним. */
private const val SAME_PLACE_IOU = 0.35f

/** Поле вокруг найденных знаков — в высотах знака. Горизонтальное больше: с краю барабана
 *  часто стоит цифра, слипшаяся с рамкой окошка, и в рамку знаков она не попала. */
private const val PAD_SIDE_HEIGHTS = 1.0f
private const val PAD_TOP_HEIGHTS = 0.4f

/**
 * Места, похожие на табло прибора, от самого правдоподобного к менее.
 *
 * Пустой список — **честный «не нашли»**, а не «здесь ничего нет»: вызывающий обязан отличить
 * это от «нашли, но цифр не собралось» (см. [MeterReadout]).
 */
fun findMeterDisplays(frame: GrayFrame, limit: Int = METER_MAX_CANDIDATES): List<MeterDisplayCandidate> {
    val minHeight = max(5, (frame.longEdge * MIN_DIGIT_SHARE).toInt())
    val maxHeight = max(minHeight + 1, (frame.longEdge * MAX_DIGIT_SHARE).toInt())
    val means = localMeans(frame, max(9, frame.longEdge / 20))
    val found = mutableListOf<MeterDisplayCandidate>()
    for (darkDigits in booleanArrayOf(true, false)) {
        val ink = inkAgainst(frame, means, INK_BIAS, darkDigits)
        stripLongRuns(ink, frame.width, frame.height, (maxHeight * 1.2f).toInt())
        val marks = biggest(components(ink, frame.width, frame.height, minHeight, maxHeight))
        if (marks.size < MIN_DIGITS) continue
        var angle = -90
        while (angle < 90) {
            val rotation = FrameRotation(angle, frame.width, frame.height)
            val placed = marks.map { mark ->
                Placed(rotation.toRotatedX(mark.cx, mark.cy), rotation.toRotatedY(mark.cx, mark.cy), mark)
            }
            for (seed in seedRows(placed)) {
                val row = alongLine(seed, placed)
                val scored = scoreRow(row, frame.longEdge) ?: continue
                found += MeterDisplayCandidate(
                    angleDegrees = angle,
                    region = rowBox(row, rotation),
                    digits = row.size,
                    digitHeight = scored.height,
                    darkDigits = darkDigits,
                    score = scored.score,
                    frame = rotation,
                )
            }
            angle += ANGLE_STEP
        }
    }
    val best = mutableListOf<MeterDisplayCandidate>()
    for (candidate in found.sortedByDescending { it.score }) {
        if (best.none { overlap(it.region, candidate.region) >= SAME_PLACE_IOU }) best += candidate
        if (best.size == limit) break
    }
    return best
}

/**
 * Рамка, по которой резать: знаки плюс поле.
 *
 * Поле — не косметика. Крайняя цифра барабана почти всегда слипается с рамкой окошка и в строку
 * знаков не попадает; вырезав ровно по найденному, мы бы систематически теряли старший или младший
 * разряд — то есть меняли показание, ничего об этом не сказав. Поле в долях высоты знака, а не в
 * пикселях: кадр приходит в любом разрешении (тот же принцип, что у полосы строки в [AtomLayer]).
 */
fun MeterDisplayCandidate.cropRegion(): Box {
    val padX = digitHeight * PAD_SIDE_HEIGHTS
    val padY = digitHeight * PAD_TOP_HEIGHTS
    return Box(
        max(0f, region.left - padX),
        max(0f, region.top - padY),
        min(frame.rotatedWidth.toFloat(), region.right + padX),
        min(frame.rotatedHeight.toFloat(), region.bottom + padY),
    )
}

/**
 * Та же рамка в масштабе полноразмерного снимка: искали на уменьшенной копии, режем из большой.
 *
 * @param full поворот полноразмерного снимка на тот же угол — его размеры и задают масштаб.
 */
fun MeterDisplayCandidate.cropRegionIn(full: FrameRotation): Box {
    val box = cropRegion()
    val kx = full.rotatedWidth.toFloat() / frame.rotatedWidth
    val ky = full.rotatedHeight.toFloat() / frame.rotatedHeight
    return Box(
        (box.left * kx).coerceIn(0f, full.rotatedWidth.toFloat()),
        (box.top * ky).coerceIn(0f, full.rotatedHeight.toFloat()),
        (box.right * kx).coerceIn(0f, full.rotatedWidth.toFloat()),
        (box.bottom * ky).coerceIn(0f, full.rotatedHeight.toFloat()),
    )
}

/** Высота знака, которую движок читает уверенно. Ниже он путает 8 и 6, выше — только память. */
const val METER_TARGET_DIGIT_PX = 60

/** Больше растягивать бессмысленно: интерполяция не добавляет того, чего в пикселях нет. */
private const val MAX_UPSCALE = 4

/**
 * Во сколько раз увеличить вырезанное, чтобы знак дорос до [METER_TARGET_DIGIT_PX].
 *
 * Целое, а не дробное: дробный масштаб размывает штрих на полпикселя, а именно по штриху движок
 * и отличает 8 от 6. Единица — «увеличивать не надо», и это обычный случай для снимка с телефона:
 * знак барабана там и так крупный. Уменьшать не умеем сознательно — потеря деталей на табло
 * необратима.
 */
fun meterUpscale(digitHeightPx: Float, target: Int = METER_TARGET_DIGIT_PX): Int {
    if (digitHeightPx <= 0f) return 1
    var scale = 1
    while (scale < MAX_UPSCALE && digitHeightPx * scale < target) scale++
    return scale
}

/**
 * Вырезанное табло, приведённое к контрасту: **знаки чёрные, фон белый — всегда**.
 *
 * Две вещи, каждая из которых по отдельности бесполезна:
 * - **порог локальный** (окно — две высоты знака): блик на барабане ярче белого щитка, и любой
 *   единый порог съедает под ним половину цифр;
 * - **полярность приводится к одной**: барабан водомера — светлые цифры на тёмном, щиток
 *   электросчётчика — тёмные на светлом. Движок обучен на чёрном по белому, и отдать ему
 *   негатив значит отдать шум. Какая полярность у этого табло, знает нашедший его кандидат
 *   ([MeterDisplayCandidate.darkDigits]) — гадать второй раз не нужно.
 */
fun meterInk(crop: GrayFrame, digitHeightPx: Float, darkDigits: Boolean): GrayFrame {
    val window = max(9, (digitHeightPx * 2).toInt())
    val means = localMeans(crop, window)
    val ink = inkAgainst(crop, means, INK_BIAS, darkDigits)
    val out = IntArray(crop.luma.size) { if (ink[it]) 0 else 255 }
    return GrayFrame(crop.width, crop.height, out)
}

// ── чтение табло: контракт ────────────────────────────────────────────────────────────────────

/**
 * Что удалось прочитать на одном табло.
 *
 * @param digits цифры дословно, слева направо. Ведущие нули барабана **не трогаются** — сколько
 *   разрядов значащие, знает поставщик услуги, а не Point ([meterWithoutDrumZeros]).
 * @param region место в координатах повёрнутой копии — по нему человеку можно показать, откуда
 *   взялось значение.
 * @param angleDegrees поворот копии, в которой считана [region].
 */
data class MeterDisplayReading(
    val digits: String,
    val region: Box,
    val angleDegrees: Int,
)

/**
 * Итог чтения прибора: что прочитали и **сколько мест вообще предложили**.
 *
 * Второе число здесь не для отладки. Без него «не нашли табло» и «нашли, но цифр не собралось»
 * приходят к человеку одинаковой пустотой, а это две разные новости: в первом случае кадр,
 * возможно, вовсе не про прибор, во втором — блик съел барабан, и стоит переснять. Тихий ноль
 * опаснее честного отказа (тот же урок, что в харнессе корпуса).
 */
data class MeterReadout(
    val displays: List<MeterDisplayReading>,
    val candidates: Int,
) {
    /** Табло не нашли вовсе — кадр, скорее всего, не про прибор. */
    val nothingFound: Boolean get() = candidates == 0

    /** Места нашлись, цифр не собралось — блик, семисегментное табло, слишком мелкий снимок. */
    val foundButUnread: Boolean get() = candidates > 0 && displays.isEmpty()

    companion object {
        val NOTHING = MeterReadout(emptyList(), candidates = 0)
    }
}

/**
 * Читает **только цифры** с табло прибора: находит место, режет, доворачивает, увеличивает,
 * приводит к контрасту и запускает движок с цифровым словарём.
 *
 * Отдельный контракт, а не режим [TextRecognizer]: обычное чтение страницы ищет строки текста на
 * всём кадре, а здесь всё наоборот — одно место, один наклон, один алфавит. Реализация — в
 * `:data` (Bitmap и движок), решение о геометрии — в [findMeterDisplays], которое тестируется без
 * устройства.
 *
 * **Пустой [MeterReadout] — не отказ чтения, а его результат.** Тот, кто может не дойти (сеть,
 * ключ, квота), обязан бросать — но этот путь офлайновый, он всегда доходит; см. [AtomRecognizer]
 * про ту же границу.
 */
interface MeterReader {
    suspend fun read(obj: PointObject): MeterReadout
}

/**
 * Сколько цифр должно собраться, чтобы считать место прочитанным.
 *
 * Три — та же граница, что у правила показания ([METER_MIN_DIGITS]): ниже начинается проза
 * («2 м³ бетону») и обрывки шума. Одно число на оба судьи взято сознательно — разъехавшиеся
 * границы означали бы «прочитали, но правило не приняло» без единого слова об этом.
 *
 * **Чего эта проверка НЕ умеет.** Сказать «это буквы, а не показание». Движок в этом пути запущен
 * с цифровым словарём, то есть выдать что-то кроме цифр он не может по построению, — и строка
 * «monobank» на квитанции приходит сюда набором цифр наравне с барабаном. Пока это так, судьёй
 * места служит человек, а не она (#262). Настоящий судья, когда до него дойдут руки, — либо
 * собственная уверенность движка (как в [weaklyRead]), либо чтение места **без** словаря с
 * требованием, чтобы прочитанное состояло из цифр.
 */
fun meterDigitsRead(raw: String): String? {
    val digits = raw.filter(Char::isDigit)
    return digits.takeIf { it.length >= METER_MIN_DIGITS }
}

// ── внутренности поиска ───────────────────────────────────────────────────────────────────────

/** На сколько яркость должна отличаться от местного среднего, чтобы считаться знаком. */
private const val INK_BIAS = 8

/**
 * Средняя яркость вокруг каждой точки — база локального порога.
 *
 * Порог локальный, а не Otsu на весь кадр (тот живёт в `ScanFilter` у скана): одно число на кадр
 * на фото прибора проигрывает — блик на барабане ярче белого щитка, тень от трубы темнее чёрного
 * окошка, и любой единый порог теряет половину цифр. Считается по интегральному изображению —
 * один проход, независимо от размера окна.
 *
 * Средние считаются **один раз на кадр** и служат обеим полярностям: тёмные знаки на светлом
 * (щиток электросчётчика) и светлые на тёмном (барабан водомера) отличаются только знаком
 * сравнения, а не окрестностью.
 */
internal fun localMeans(frame: GrayFrame, window: Int): IntArray {
    val w = frame.width
    val h = frame.height
    val sums = LongArray((w + 1) * (h + 1))
    for (y in 0 until h) {
        var rowSum = 0L
        for (x in 0 until w) {
            rowSum += frame.at(x, y)
            sums[(y + 1) * (w + 1) + x + 1] = sums[y * (w + 1) + x + 1] + rowSum
        }
    }
    val radius = max(1, window / 2)
    val means = IntArray(w * h)
    for (y in 0 until h) {
        val y0 = max(0, y - radius)
        val y1 = min(h - 1, y + radius)
        for (x in 0 until w) {
            val x0 = max(0, x - radius)
            val x1 = min(w - 1, x + radius)
            val area = (x1 - x0 + 1) * (y1 - y0 + 1)
            val sum = sums[(y1 + 1) * (w + 1) + x1 + 1] - sums[y0 * (w + 1) + x1 + 1] -
                sums[(y1 + 1) * (w + 1) + x0] + sums[y0 * (w + 1) + x0]
            means[y * w + x] = (sum / area).toInt()
        }
    }
    return means
}

/** Точки, отличающиеся от местного среднего на [bias] в нужную сторону, — знаки, а не фон. */
internal fun inkAgainst(frame: GrayFrame, means: IntArray, bias: Int, dark: Boolean): BooleanArray {
    val ink = BooleanArray(frame.luma.size)
    for (i in ink.indices) {
        val value = frame.luma[i]
        ink[i] = if (dark) value < means[i] - bias else value > means[i] + bias
    }
    return ink
}

/**
 * Стирает пробеги длиннее [maxRun] — по строкам и по столбцам.
 *
 * Это рамка окошка, кромка щитка, провод: линии, а не знаки. Пока они на месте, цифры барабана
 * слипаются с ними в одно пятно во весь кадр, и никакой разбор на знаки невозможен — ровно на этом
 * первые версии поиска не видели табло вовсе.
 */
internal fun stripLongRuns(ink: BooleanArray, width: Int, height: Int, maxRun: Int) {
    if (maxRun <= 0) return
    for (y in 0 until height) {
        var x = 0
        while (x < width) {
            if (!ink[y * width + x]) { x++; continue }
            var end = x
            while (end < width && ink[y * width + end]) end++
            if (end - x > maxRun) for (i in x until end) ink[y * width + i] = false
            x = end
        }
    }
    for (x in 0 until width) {
        var y = 0
        while (y < height) {
            if (!ink[y * width + x]) { y++; continue }
            var end = y
            while (end < height && ink[end * width + x]) end++
            if (end - y > maxRun) for (i in y until end) ink[i * width + x] = false
            y = end
        }
    }
}

/** Связное пятно: рамка, центр и размеры. */
internal class InkMark(val left: Int, val top: Int, val right: Int, val bottom: Int, val pixels: Int) {
    val w: Int get() = right - left + 1
    val h: Int get() = bottom - top + 1
    val cx: Float get() = (left + right + 1) / 2f
    val cy: Float get() = (top + bottom + 1) / 2f
}

/** Ниже этой заполненности рамки пятно — не знак, а царапина по диагонали. */
private const val MIN_FILL = 0.12f

/** Знак шире этого (в своих высотах) — слипшаяся пара, а не цифра. */
private const val MAX_DIGIT_ASPECT = 1.6f

/** Связные пятна подходящего под знак размера (связность по четырём соседям). */
internal fun components(ink: BooleanArray, width: Int, height: Int, minHeight: Int, maxHeight: Int): List<InkMark> {
    val seen = BooleanArray(ink.size)
    val stack = IntArray(ink.size)
    val marks = mutableListOf<InkMark>()
    for (start in ink.indices) {
        if (!ink[start] || seen[start]) continue
        var top = 0
        var size = 0
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        stack[top++] = start
        seen[start] = true
        while (top > 0) {
            val p = stack[--top]
            val x = p % width
            val y = p / width
            size++
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            if (x > 0 && ink[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack[top++] = p - 1 }
            if (x < width - 1 && ink[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack[top++] = p + 1 }
            if (y > 0 && ink[p - width] && !seen[p - width]) { seen[p - width] = true; stack[top++] = p - width }
            if (y < height - 1 && ink[p + width] && !seen[p + width]) {
                seen[p + width] = true
                stack[top++] = p + width
            }
        }
        val boxH = maxY - minY + 1
        val boxW = maxX - minX + 1
        if (boxH < minHeight || boxH > maxHeight) continue
        if (boxW < 2 || boxW > boxH * MAX_DIGIT_ASPECT) continue
        if (size < boxW * boxH * MIN_FILL) continue
        marks += InkMark(minX, minY, maxX, maxY, size)
    }
    return marks
}

/**
 * Столько пятен максимум идёт в перебор наклонов.
 *
 * Фото прибора во дворе — это ещё и гравий, трава и штукатурка: пятен подходящего размера там
 * тысячи, а перебор стоит их числа на каждый из 36 наклонов. Порог назван вслух, потому что он
 * **может отбросить настоящую цифру**: остаются самые крупные пятна, а цифра табло крупнее
 * крапины на камне — но не всегда крупнее листа травы. Цена ошибки здесь — промах поиска, то есть
 * честный отказ и следующее звено цепочки; цена отсутствия порога — секунды ожидания на каждом
 * фото, снятом на улице.
 */
private const val MAX_MARKS = 1500

/** Самые крупные пятна, если их слишком много; иначе — те же и в том же порядке. */
private fun biggest(marks: List<InkMark>): List<InkMark> =
    if (marks.size <= MAX_MARKS) marks else marks.sortedByDescending { it.pixels }.take(MAX_MARKS)

/** Пятно на своём месте в повёрнутой копии. */
internal class Placed(val x: Float, val y: Float, val mark: InkMark)

/** Насколько центры могут разойтись по вертикали, оставаясь одной строкой (в высотах знака). */
private const val ROW_TOLERANCE = 0.5f

/** Заготовки строк: жадная раскладка по горизонтальным полосам. */
private fun seedRows(placed: List<Placed>): List<List<Placed>> {
    val rows = mutableListOf<MutableList<Placed>>()
    val centers = mutableListOf<Float>()
    val heights = mutableListOf<Float>()
    placed.sortedBy { it.y }.forEach { p ->
        val index = rows.indices.firstOrNull { i ->
            abs(centers[i] - p.y) <= ROW_TOLERANCE * p.mark.h && heights[i] / p.mark.h in 0.65f..1.55f
        }
        if (index == null) {
            rows += mutableListOf(p)
            centers += p.y
            heights += p.mark.h.toFloat()
        } else {
            rows[index] += p
            val k = rows[index].size
            centers[index] = (centers[index] * (k - 1) + p.y) / k
            heights[index] = (heights[index] * (k - 1) + p.mark.h) / k
        }
    }
    return rows.filter { it.size >= MIN_DIGITS - 1 }
}

/**
 * Досбор строки по подогнанной прямой.
 *
 * Жадная раскладка теряет знаки: цифра, чей центр на пиксель ближе к соседней полосе, уходит в неё
 * и в барабан уже не возвращается. На кадре 17 корпуса из-за этого от семизначного табло
 * оставалось четыре цифры — то есть показание, короче настоящего на три разряда. Поэтому по
 * заготовке строится прямая (обычная МНК-подгонка), и вдоль неё собираются **все** пятна
 * подходящей высоты, независимо от того, кому они достались раньше.
 */
private fun alongLine(seed: List<Placed>, all: List<Placed>): List<Placed> {
    if (seed.size < 2) return seed
    val n = seed.size
    val meanX = seed.sumOf { it.x.toDouble() } / n
    val meanY = seed.sumOf { it.y.toDouble() } / n
    var num = 0.0
    var den = 0.0
    seed.forEach {
        num += (it.x - meanX) * (it.y - meanY)
        den += (it.x - meanX) * (it.x - meanX)
    }
    val slope = if (den < 1e-6) 0.0 else num / den
    val intercept = meanY - slope * meanX
    val height = seed.sumOf { it.mark.h.toDouble() } / n
    return all.filter { p ->
        abs(p.y - (slope * p.x + intercept)) <= ROW_TOLERANCE * height &&
            height / p.mark.h in 0.65..1.55
    }.sortedBy { it.x }
}

private class RowScore(val height: Float, val score: Float)

/**
 * Наклон, при котором строка ещё считается горизонтальной в этом довороте.
 *
 * Проверка обязательна, потому что [alongLine] собирает знаки **вдоль подогнанной прямой**, а не
 * вдоль горизонтали: без неё наклонённый на 20° барабан находится уже при доворотe в −5°, и
 * найденный «угол» перестаёт означать то, что означает. Цена молчаливая и дорогая — по этому углу
 * потом доворачивают кадр перед чтением, и движок получает косые цифры, будучи уверенным, что
 * они выпрямлены.
 */
private const val MAX_ROW_SLOPE = 0.10f

/** Строка знаков как барабан: одинаковые, равномерные, длинная, горизонтальная. */
private fun scoreRow(row: List<Placed>, longEdge: Int): RowScore? {
    if (row.size < MIN_DIGITS || row.size > MAX_DIGITS) return null
    if (abs(rowSlope(row)) > MAX_ROW_SLOPE) return null
    val heights = row.map { it.mark.h.toFloat() }
    val widths = row.map { it.mark.w.toFloat() }
    val gaps = row.zipWithNext { a, b -> b.x - a.x }
    if (gaps.isEmpty() || gaps.min() <= 0f) return null
    val heightSpread = spread(heights) ?: return null
    val widthSpread = spread(widths) ?: return null
    val gapSpread = spread(gaps) ?: return null
    if (heightSpread > MAX_HEIGHT_SPREAD || widthSpread > MAX_WIDTH_SPREAD || gapSpread > MAX_GAP_SPREAD) return null
    val meanHeight = heights.average().toFloat()
    val pitch = gaps.average().toFloat() / widths.average().toFloat()
    if (pitch < MIN_PITCH || pitch > MAX_PITCH) return null
    if (row.last().x - row.first().x < MIN_SPAN_HEIGHTS * meanHeight) return null
    val score = row.size * (meanHeight / longEdge) *
        (1 - heightSpread) * (1 - widthSpread) * (1 - gapSpread)
    return RowScore(meanHeight, score)
}

/** Наклон строки в этом довороте — обычная МНК-подгонка по центрам знаков. */
private fun rowSlope(row: List<Placed>): Float {
    val meanX = row.sumOf { it.x.toDouble() } / row.size
    val meanY = row.sumOf { it.y.toDouble() } / row.size
    var num = 0.0
    var den = 0.0
    row.forEach {
        num += (it.x - meanX) * (it.y - meanY)
        den += (it.x - meanX) * (it.x - meanX)
    }
    return if (den < 1e-6) 0f else (num / den).toFloat()
}

/** Разброс как доля от среднего; `null` — среднее нулевое, судить нечего. */
private fun spread(values: List<Float>): Float? {
    val mean = values.average()
    if (mean <= 0.0) return null
    val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
    return (sqrt(variance) / mean).toFloat()
}

/** Рамка строки в координатах повёрнутой копии. */
private fun rowBox(row: List<Placed>, rotation: FrameRotation): Box =
    row.map { rotation.toRotated(Box(it.mark.left.toFloat(), it.mark.top.toFloat(), (it.mark.right + 1).toFloat(), (it.mark.bottom + 1).toFloat())) }
        .reduce(Box::union)

/** Доля пересечения к объединению — ею и решается, что два места одно и то же. */
private fun overlap(a: Box, b: Box): Float {
    val left = max(a.left, b.left)
    val top = max(a.top, b.top)
    val right = min(a.right, b.right)
    val bottom = min(a.bottom, b.bottom)
    if (right <= left || bottom <= top) return 0f
    val inter = (right - left) * (bottom - top)
    val union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - inter
    return if (union <= 0f) 0f else inter / union
}

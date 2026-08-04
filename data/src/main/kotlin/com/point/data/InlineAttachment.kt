package com.point.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.point.core.flow.readingUpscale
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/** Что реально уедет в запрос модели: base64 и **фактический** mime отправленных байтов. */
internal class InlineAttachment(val base64: String, val mime: String)

/**
 * Единственное место, где файл превращается во вложение запроса к модели.
 *
 * **Живой прецедент (#200).** Эталонная ведомость владельца — фото 4000×3000, 3.2 МБ. «В Excel»
 * отправляет её моделям ради консенсуса, то есть по 4.3 МБ base64 в каждый запрос. #334 ввёл
 * пиксельный предел 3072 px по длинной стороне — из веры провайдерским докам («Gemini сам ужимает
 * вход под 3072, выше не смотрит никто»), то есть в расчёте, что их ужатие и наше — одно и то же.
 *
 * **Серия замеров показала обратное — пиксельный повод ужимать снят (см. `docs/DECISIONS.md`).**
 * Серия чтений ведомости двумя моделями (полный кадр / 3072 / 2048, каждое чтение мерялось
 * метрикой продукта `:core:flow:scoreTable`): полный кадр устойчиво лучше нашего ужатия у обеих —
 * у зоркой модели каждое чтение полного кадра выше любого чтения ужатого (47–48 против 38–46
 * совпавших ячеек из 49), а вторая модель на ужатых кадрах разваливается совсем (транспонирует
 * таблицу, дописывает несуществующие строки): 0 из 49 во всех десяти чтениях обоих ужатий при
 * 38–46 на полном кадре. Что провайдер делает со входом у себя — его дело; наш пре-даунскейл
 * выбрасывал качество, которое их собственный конвейер сохраняет.
 *
 * Поэтому кадр, влезающий в байтовый бюджет [MODEL_INLINE_BUDGET_BYTES], уезжает целиком.
 * Ужатие осталось только как ответ на **вес**: кадр тяжелее бюджета вписывается в
 * [MODEL_MAX_EDGE_PX] и жмётся в JPEG — плата за транспорт, и цена её теперь измерена, а не
 * угадана. Ужатый кадр выпрямляется по EXIF ([decodeBoundedUpright] — тот же урок «OCR-каши» на
 * боковом фото) и едет уже пикселями, а не меткой поворота, которую получатель волен
 * проигнорировать. Кадр, уехавший как есть, ведёт себя ровно как раньше.
 *
 * **Вторая половина той же мысли — увеличение** (#273, [enlargedFrame]): «не ужимать» чинит кадр,
 * который родился большим, и ничего не делает с кадром, который родился мелким. Замер 04.08.2026
 * показал, что мелкий кадр — единственный, на котором чтение вообще провалилось, и что обычное
 * увеличение вчетверо его чинит целиком.
 *
 * Возвращает null, когда прикладывать нечего: файла нет, он пуст или не влезает в жёсткий потолок
 * [MAX_INLINE_BYTES]. Заодно закрыт тихий провал (#334): снимок тяжелее потолка раньше просто не
 * прикладывался — модель получала один текст промпта и отвечала про несуществующую страницу.
 * Такой кадр доезжает ужатым, а без картинки остаётся только то, что и картинкой-то не
 * распозналось.
 */
internal fun inlineAttachment(path: String, mime: String): InlineAttachment? {
    val file = File(path)
    val size = if (file.exists()) file.length() else 0L
    if (size < 1L) return null
    shrunkFrame(file, mime)?.let { return InlineAttachment(base64(it.bytes), it.mime) }
    enlargedFrame(file, mime)?.let { return InlineAttachment(base64(it.bytes), it.mime) }
    // Кадр в пределах бюджета (и PDF, где ужимать нечего) уезжает как есть.
    if (size > MAX_INLINE_BYTES) return null
    return InlineAttachment(base64(file.readBytes()), mime)
}

/**
 * Мелкий кадр, увеличенный перед отправкой модели (#273), либо null — увеличивать не нужно, нечем
 * или незачем.
 *
 * **Это ровно та дорога, на которой замер получен.** Из шести порч эталонной ведомости
 * (`docs/VISION-MODELS.md`, 04.08.2026) провалилась одна — кадр в четверть разрешения: 20 строк из
 * 24 и итог мимо на 8300. Тот же кадр, увеличенный вчетверо и отданный той же модели, прочитался
 * дословно: 24 из 24, четыре секунды. Увеличение локальное — наружу по-прежнему уходит один кадр
 * по одному тапу, ни одного лишнего посредника.
 *
 * Порядок с [shrunkFrame] не спорит: тот включается **по весу** (кадр тяжелее бюджета), этот — по
 * размеру мелкого кадра, и мелкий кадр тяжёлым не бывает. Пробуется он вторым, чтобы у ужатия
 * осталось последнее слово в невероятном случае, когда сработали бы оба.
 *
 * Прозрачность и здесь переживает только PNG — вырезанный объект (#97), перекодированный в JPEG,
 * приехал бы к модели на чёрном фоне.
 */
private fun enlargedFrame(file: File, mime: String): Frame? {
    if (!mime.startsWith("image/")) return null
    return runCatching {
        // Решение принимается ПО ЗАГОЛОВКУ файла, до единого декодированного пикселя: сюда
        // приходит каждый запрос к модели с картинкой, и поднимать эталонную ведомость в память
        // (48 МБ) ради ответа «увеличивать не надо» значило бы платить за приём на всех кадрах,
        // где он не работает. Поворот EXIF ответа не меняет — правило смотрит на длинную сторону
        // и на площадь, а они от разворота не зависят.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val scale = readingUpscale(bounds.outWidth, bounds.outHeight)
        if (scale <= 1) return null
        val upright = decodeBoundedUpright(file.path, MODEL_MAX_EDGE_PX) ?: return null
        val scaled = Bitmap.createScaledBitmap(upright, upright.width * scale, upright.height * scale, true)
        if (scaled !== upright) upright.recycle()
        val png = scaled.hasAlpha()
        val out = ByteArrayOutputStream()
        scaled.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        scaled.recycle()
        val bytes = out.toByteArray()
        // Увеличенный кадр весит больше исходного — это плата за читаемость, а не оплошность. Но
        // выше жёсткого потолка вложения его никто не примет, и тогда честнее отправить оригинал.
        if (bytes.size > MAX_INLINE_BYTES) null else Frame(bytes, if (png) "image/png" else "image/jpeg")
    }.getOrNull()
}

/** Ужатая копия кадра, либо null — если ужимать не нужно, нечем или бессмысленно. */
private fun shrunkFrame(file: File, mime: String): Frame? {
    if (!mime.startsWith("image/")) return null
    if (!oversizedForModel(file.length())) return null
    return runCatching {
        val upright = decodeBoundedUpright(file.path, MODEL_MAX_EDGE_PX) ?: return null
        val (w, h) = fittedSize(upright.width, upright.height, MODEL_MAX_EDGE_PX)
        val scaled = if (w == upright.width && h == upright.height) {
            upright
        } else {
            Bitmap.createScaledBitmap(upright, w, h, true).also { if (it !== upright) upright.recycle() }
        }
        // Прозрачность переживает только PNG: вырезанный объект («Убрать фон», #97),
        // перекодированный в JPEG, приехал бы к модели на чёрном фоне — это не «мельче»,
        // это другое изображение.
        val png = scaled.hasAlpha()
        val out = ByteArrayOutputStream()
        scaled.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        scaled.recycle()
        val bytes = out.toByteArray()
        // Перекодировка, которая не стала меньше, — чистая потеря качества: отправляем оригинал.
        if (bytes.size >= file.length()) null else Frame(bytes, if (png) "image/png" else "image/jpeg")
    }.getOrNull()
}

private class Frame(val bytes: ByteArray, val mime: String)

/** Кадр тяжелее бюджета отправки — единственный повод ужимать. Пиксельного повода нет: серия
 *  на эталонной ведомости показала, что пре-даунскейл до «предела провайдера» стоит качества
 *  чтения (см. KDoc [inlineAttachment]). Чистая функция — проверяется юнит-тестом на числах
 *  ведомости. */
internal fun oversizedForModel(
    bytes: Long,
    budget: Long = MODEL_INLINE_BUDGET_BYTES,
): Boolean = bytes > budget

/** Размер, вписанный в предел по длинной стороне с сохранением пропорций (4000×3000 → 3072×2304). */
internal fun fittedSize(width: Int, height: Int, maxEdgePx: Int = MODEL_MAX_EDGE_PX): Pair<Int, Int> {
    val longEdge = maxOf(width, height)
    if (longEdge <= 0 || longEdge <= maxEdgePx) return width to height
    val k = maxEdgePx.toDouble() / longEdge
    return maxOf(1, (width * k).roundToInt()) to maxOf(1, (height * k).roundToInt())
}

private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

/** Жёсткий потолок инлайна — общий для трёх клиентов (был скопирован в каждом). Выше него
 *  вложения просто нет: запрос такого размера провайдеры отбивают, и молчаливая отправка в никуда
 *  хуже честного «без картинки». */
internal const val MAX_INLINE_BYTES = 15L * 1024 * 1024

/**
 * Во что вписывается кадр, когда ужимать его заставил вес, — **цель** ужатия, а не повод.
 * Поводом размер в пикселях был один срез (#334) и был им зря: серия чтений показала, что модель
 * читает полный кадр лучше, чем наш даунскейл до её же документированного предела (цифры — в
 * KDoc [inlineAttachment] и `docs/DECISIONS.md`). Для кадра тяжелее бюджета 3072 остаётся целью:
 * на ведомости это байты вчетверо вниз, и это же — документированный предел самого зоркого из
 * читателей (Gemini). Мельчить серия повода не дала: у второй модели 2048 разваливается так же,
 * как 3072, — в нули.
 */
internal const val MODEL_MAX_EDGE_PX = 3072

/**
 * Байтовый бюджет кадра — единственный повод ужимать: легче — уезжает целиком, тяжелее —
 * вписывается в [MODEL_MAX_EDGE_PX] и перекодируется (скрин таблицы на 9 МБ ужимается в разы
 * без единого потерянного пикселя разметки).
 *
 * **Граница, которую легко прочитать шире, чем она есть.** Кадр в пределах по пикселям ужимает
 * ОДНА перекодировка — а для прозрачного кадра это PNG в PNG: без потерь, а значит и без гарантии
 * выигрыша. Не выиграло — уезжает оригинал («не стало меньше — отправляем как есть»), то есть на
 * кадре с альфа-каналом бюджет может не сработать вовсе. Это осознанный выбор в пользу картинки
 * (JPEG положил бы вырезанный объект #97 на чёрный фон), а не забытая ветка. Кадры тяжелее
 * бюджета по-прежнему платят ужатием — что оно стоит качества, серия показала на кадре ЛЕГЧЕ
 * бюджета, а мерить эту цену на тяжёлых кадрах будет живой кадр, когда такой появится в корпусе.
 */
internal const val MODEL_INLINE_BUDGET_BYTES = 4L * 1024 * 1024

/** Качество JPEG для документа: 90 — практически неразличимо на тексте, но вдвое легче исходного
 *  кадра камеры. Ниже начинают звенеть тонкие линии таблицы, а их модель читает как границы ячеек. */
private const val JPEG_QUALITY = 90

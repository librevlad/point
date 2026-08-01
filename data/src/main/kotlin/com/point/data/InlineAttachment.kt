package com.point.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/** Что реально уедет в запрос модели: base64 и **фактический** mime отправленных байтов. */
internal class InlineAttachment(val base64: String, val mime: String)

/**
 * Единственное место, где файл превращается во вложение запроса к модели.
 *
 * **Живой прецедент (#200).** Эталонная ведомость владельца — фото 4000×3000, 3.2 МБ. «В Excel»
 * отправляет её ДВУМ моделям ради консенсуса, то есть по 4.3 МБ base64 в каждый запрос. При этом
 * выше ~3072 px по длинной стороне на кадр не смотрит ни один получатель: Gemini сам ужимает вход
 * под 3072, Claude — под 1568. Мы платили временем загрузки за пиксели, которые адресат выбрасывает
 * у себя, причём дважды. До этой правки даунскейла не было нигде: три клиента (`GeminiLlmClient`,
 * `ClaudeLlmClient`, `OpenAiCompatibleClient`) читали файл целиком и одинаково — три копии одной
 * дыры, поэтому предел живёт здесь один, а не в каждом клиенте.
 *
 * **Почему 3072, а не 1568.** Предел взят по САМОМУ зоркому из читателей и с запасом: качество
 * таблицы дороже байтов. На ведомости строка занимает ~85 px, глиф ~40 px; после 0.77× остаётся
 * ~31 px — заведомо читаемо, и ни один пиксель, который модель бы посмотрела, не потерян. Ужимать
 * до клодовских 1568 значило бы решать за Gemini, что ему хватит.
 *
 * **Что ещё меняется у ужатого кадра.** Он выпрямляется по EXIF ([decodeBoundedUpright] — тот же
 * урок «OCR-каши» на боковом фото) и едет уже пикселями, а не с меткой поворота, которую получатель
 * волен проигнорировать. Кадр, уехавший как есть (в пределах бюджета), ведёт себя ровно как раньше.
 *
 * Возвращает null, когда прикладывать нечего: файла нет, он пуст или не влезает в жёсткий потолок
 * [MAX_INLINE_BYTES]. Заодно чинится тихий провал: снимок тяжелее потолка раньше просто не
 * прикладывался — модель получала один текст промпта и отвечала про несуществующую страницу.
 * Теперь такой кадр доезжает ужатым, а без картинки остаётся только то, что и картинкой-то не
 * распозналось.
 */
internal fun inlineAttachment(path: String, mime: String): InlineAttachment? {
    val file = File(path)
    val size = if (file.exists()) file.length() else 0L
    if (size < 1L) return null
    shrunkFrame(file, mime)?.let { return InlineAttachment(base64(it.bytes), it.mime) }
    // Кадр в пределах бюджета (и PDF, где ужимать нечего) уезжает как есть — как раньше.
    if (size > MAX_INLINE_BYTES) return null
    return InlineAttachment(base64(file.readBytes()), mime)
}

/** Ужатая копия кадра, либо null — если ужимать не нужно, нечем или бессмысленно. */
private fun shrunkFrame(file: File, mime: String): Frame? {
    if (!mime.startsWith("image/")) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0 || !oversizedForModel(longEdge, file.length())) return null
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

/** Кадр крупнее того, что модель вообще посмотрит, либо тяжелее бюджета отправки. Чистая функция —
 *  проверяется юнит-тестом на числах эталонной ведомости. */
internal fun oversizedForModel(
    longEdgePx: Int,
    bytes: Long,
    maxEdgePx: Int = MODEL_MAX_EDGE_PX,
    budget: Long = MODEL_INLINE_BUDGET_BYTES,
): Boolean = longEdgePx > maxEdgePx || bytes > budget

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

/** Предел длинной стороны отправляемого кадра — см. KDoc [inlineAttachment]. */
internal const val MODEL_MAX_EDGE_PX = 3072

/** Байтовый бюджет кадра: тяжелее — перекодируем, даже если по пикселям он в пределе (PNG-скрин
 *  таблицы на 9 МБ ужимается вчетверо без единого потерянного пикселя разметки). */
internal const val MODEL_INLINE_BUDGET_BYTES = 4L * 1024 * 1024

/** Качество JPEG для документа: 90 — практически неразличимо на тексте, но вдвое легче исходного
 *  кадра камеры. Ниже начинают звенеть тонкие линии таблицы, а их модель читает как границы ячеек. */
private const val JPEG_QUALITY = 90

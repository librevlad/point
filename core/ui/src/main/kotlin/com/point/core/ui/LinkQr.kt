package com.point.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.point.core.flow.qrMatrix
import kotlin.math.floor

/**
 * Ссылка кодом (#388): навёл камерой другого устройства — забрал, ничего не пересылая.
 *
 * Рисование — и только оно: сам код считает `qrMatrix` в Android-free ядре, сюда приезжает готовая
 * матрица модулей. Ничего не пишется в файл и никуда не отправляется — код существует ровно
 * столько, сколько на него смотрят.
 *
 * **Белая плита в тёмном портале — намеренно.** Инвертированный код (светлые модули на тёмном)
 * читают далеко не все камеры, а код, который не читается, хуже отсутствующего: человек стоит и
 * водит телефоном. Поэтому здесь единственное место, где Point светит белым, и цвета заданы
 * жёстко, а не темой.
 */
@Composable
fun LinkQr(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    val matrix = remember(text) { qrMatrix(text) } ?: return
    val plate = RoundedCornerShape(14.dp)
    Canvas(
        modifier = modifier
            .size(size)
            .clip(plate)
            .background(Color.White)
            // Экран озвучивает код словами: слепому человеку картинка не говорит ничего, а
            // ссылка рядом — говорит.
            .semantics { contentDescription = "QR-код ссылки" },
    ) {
        // Поле тишины (4 модуля с каждой стороны) обязательно: без него камера не найдёт границу
        // кода. Шаг округляем вниз до целого пикселя — иначе на дробном шаге между модулями
        // проступают щели, и код «плывёт».
        val quiet = 4
        val cells = matrix.size + quiet * 2
        val step = floor(this.size.minDimension / cells)
        val offset = (this.size.minDimension - step * cells) / 2f + step * quiet
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (!matrix[x, y]) continue
                drawRect(
                    color = Color.Black,
                    topLeft = androidx.compose.ui.geometry.Offset(offset + x * step, offset + y * step),
                    size = androidx.compose.ui.geometry.Size(step, step),
                )
            }
        }
    }
}

/**
 * Ссылка, которую Point **сам только что выдал**, — или `null`.
 *
 * Кодом показывается только она. Чужой URL-объект (человек расшарил ссылку из браузера) кодом не
 * показывается: эта ссылка у него уже есть, и рисовать её ещё раз — шум. Признак свой:
 * `drop.expires` кладёт действие «Дать ссылку», и он же означает «ссылка временная и публичная».
 */
internal fun issuedLinkOf(metadata: Map<String, String>): String? {
    if (metadata[META_DROP_EXPIRES].isNullOrBlank()) return null
    return metadata[META_ENTITY_URL]?.takeIf { it.isNotBlank() }
}

/** Цена ссылки словами: срок жизни берётся из метаданных, а не сочиняется экраном. */
internal fun issuedLinkWarning(metadata: Map<String, String>): String {
    val expires = metadata[META_DROP_EXPIRES]?.takeIf { it.isNotBlank() }
    val life = if (expires == null) "" else " Живёт $expires."
    return "Заберёт любой, у кого есть ссылка: файл лежит на сервере открытым.$life"
}

internal const val META_ENTITY_URL = "entity.url"
internal const val META_DROP_EXPIRES = "drop.expires"

/**
 * Карточка ссылки: код, сама ссылка словами и **цена, названная вслух**.
 *
 * Ссылка Point — публичная: у того, кто её открывает, нет ни аккаунта, ни ключа, значит файл лежит
 * на сервере открытым, и любой, кому ссылку переслали дальше, тоже его заберёт. Человек обязан
 * прочитать это ДО того, как отправит ссылку кому-то, а не узнать потом. Поэтому предупреждение —
 * часть карточки, а не примечание где-то в другом месте.
 *
 * Ссылка показана и текстом: код читает камера, а переслать в мессенджер человек может только
 * текст — и увидеть, **куда** уезжает объект, тоже можно только по тексту (какой это сервер).
 */
@Composable
fun LinkCard(
    url: String,
    title: String,
    warning: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .widthIn(max = 340.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(RowTop, RowBottom)))
            .border(1.dp, Brush.verticalGradient(listOf(TopHighlight, Color.Transparent)), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        LinkQr(url)
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = warning,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )
    }
}

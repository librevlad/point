package com.point.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.point.core.flow.Box as PageBox

/**
 * Экран поиска по документу (#279): страница целиком и подсвеченные места, где нашлось.
 *
 * Это тот же экран страницы, что у выделения (#259), с одной разницей: рамку рисует не палец, а
 * запрос. Поэтому геометрия у них общая ([pageFit], [drawPageHighlights]) — подсветка обязана
 * лечь ровно на то слово, про которое сказано.
 *
 * Экран нем по части логики: получает битмап и подсветку **в координатах битмапа**, отдаёт
 * набранный запрос. Сам поиск, страницы и сырой кадр живут у вызывающего.
 *
 * [status] — то, что Point говорит о находках: `null` значит «ещё не искали», и это не то же
 * самое, что «ничего не нашлось». Показывать пустое поле как неудачный поиск — врать в ответ на
 * бездействие.
 */
@Composable
fun FindScreen(
    image: ImageBitmap,
    highlights: List<PageBox>,
    status: String?,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Набранное живёт в поле, как у остальных полей ввода приложения: буквы не ездят
        // кругом через состояние экрана и не отстают от пальца.
        var query by remember { mutableStateOf("") }
        // Поле сверху, страница под ним: экранная клавиатура закрывает низ экрана, и поле,
        // спрятанное под ней, — это поиск, в который нельзя посмотреть.
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; onQuery(it) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Что найти") },
                    )
                    TextButton(onClick = onClose) { Text("Закрыть") }
                }
                if (status != null) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        var container by remember { mutableStateOf(IntSize.Zero) }
        val fit = pageFit(container, image.width, image.height)
        val accent = MaterialTheme.colorScheme.primary

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { container = it }
                .drawWithContent {
                    drawContent()
                    drawPageHighlights(
                        fit = fit,
                        boxes = highlights,
                        color = accent,
                        cornerPx = 4.dp.toPx(),
                        strokePx = 1.5f.dp.toPx(),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

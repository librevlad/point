package com.point.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.point.core.flow.qrMatrix
import kotlin.math.floor

@Composable
fun LinkQr(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    val matrix = remember(text) { qrMatrix(text) } ?: return
    val plate = PortalPlateShape
    Canvas(
        modifier = modifier
            .size(size)
            .clip(plate)
            .background(Color.White)

            .semantics { contentDescription = "QR-код ссылки" },
    ) {

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

internal fun issuedLinkOf(metadata: Map<String, String>): String? {
    if (metadata[META_DROP_EXPIRES].isNullOrBlank()) return null
    return metadata[META_ENTITY_URL]?.takeIf { it.isNotBlank() }
}

internal fun issuedLinkWarning(metadata: Map<String, String>): String {
    val expires = metadata[META_DROP_EXPIRES]?.takeIf { it.isNotBlank() }
    val life = if (expires == null) "" else " Живёт $expires."
    return "Заберёт любой, у кого есть ссылка: файл лежит на сервере открытым.$life"
}

internal const val META_ENTITY_URL = "entity.url"
internal const val META_DROP_EXPIRES = "drop.expires"

@Composable
fun LinkCard(
    url: String,
    title: String,
    warning: String,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .widthIn(max = PortalColumnWidth)
            .portalCard(elevation = 0.dp)
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

package com.point.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.model.ObjectKind
import com.point.core.model.tr
import com.point.desktop.InboxItem
import com.point.core.ui.GlassIcons
import com.point.core.ui.GlassTone
import com.point.core.ui.kindIcon
import com.point.core.ui.kindTint

/**
 * Плашка прибытия — своё окошко Point, не системное уведомление: стекло того же окна, вид и имя
 * пришедшего. Щелчок открывает его в окне, крестик убирает плашку.
 */
@Composable
fun PeekCard(
    item: InboxItem,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    source: com.point.desktop.ObjectSource = com.point.desktop.ObjectSource.PHONE_RELAY,
) {
    GlassWindow(Modifier.fillMaxSize(), tone = GlassTone.PANEL, shape = RoundedCornerShape(16.dp), line = null) {
        Row(
            modifier = Modifier.fillMaxSize().handCursor().clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeekThumb(item)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when (source) {
                        com.point.desktop.ObjectSource.LOCAL -> tr("ГОТОВО НА КОМПЬЮТЕРЕ")
                        else -> tr("ПРИШЛО С ТЕЛЕФОНА")
                    },
                    style = PointType.label,
                )
                Text(
                    item.obj.metadata["name"] ?: tr("Объект"),
                    style = PointType.rowTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PeekHint(item)
            }
            RoundButton(GlassIcons.Close, tr("Скрыть"), onDismiss)
        }
    }
}

/** Превью в плашке: картинке — миниатюра, остальному — знак вида на плашке его тона. */
@Composable
private fun PeekThumb(item: InboxItem) {
    val image = if (item.obj.state.kind == ObjectKind.IMAGE) {
        remember(item.obj.uri.value) {
            runCatching {
                java.io.File(item.obj.uri.value).inputStream().use { androidx.compose.ui.res.loadImageBitmap(it) }
            }.getOrNull()
        }
    } else {
        null
    }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Plate(
                icon = kindIcon(item.obj.state.kind),
                tint = kindTint(item.obj.state.kind),
                size = 48.dp,
                iconSize = 20.dp,
                contentDescription = com.point.core.ui.kindLabel(item.obj.state.kind),
            )
        }
    }
}

/** Тексту — первые слова: по ним видно, что пришло, без входа в окно. */
@Composable
private fun PeekHint(item: InboxItem) {
    if (item.obj.state.kind != ObjectKind.TEXT) return
    val line = remember(item.obj.uri.value) {
        runCatching {
            java.io.File(item.obj.uri.value).useLines { lines -> lines.firstOrNull { it.isNotBlank() } }
        }.getOrNull()?.trim()?.take(70)
    }
    if (!line.isNullOrBlank()) {
        Text(line, style = PointType.note, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

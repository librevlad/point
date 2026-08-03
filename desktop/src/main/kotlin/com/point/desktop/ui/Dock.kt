package com.point.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.desktop.InboxItem

/**
 * Док «Прилетело» (#285): всё, что пришло на ПК, вдоль левого края.
 *
 * Здесь видна **передача с телефона**: объект прилетел — он тут же появляется в доке, и человек
 * выбирает, с каким работать. Выбранный уходит в конвейер справа.
 */
@Composable
fun Dock(
    items: List<InboxItem>,
    selected: InboxItem?,
    onSelect: (InboxItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(244.dp).fillMaxHeight()
            .background(PointColors.window.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("ПРИЛЕТЕЛО", style = PointType.label, modifier = Modifier.padding(horizontal = 4.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { item ->
                DockItem(item, selected = item === selected, onClick = { onSelect(item) })
            }
        }

        DropHint()
    }
}

@Composable
private fun DockItem(item: InboxItem, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    Brush.verticalGradient(listOf(PointColors.surface, PointColors.surfaceDeep))
                } else {
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                },
            )
            .border(
                1.dp,
                if (selected) PointColors.violet.copy(alpha = 0.55f) else PointColors.border.copy(alpha = 0.7f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(7.dp)
                .background(if (selected) PointColors.violet else PointColors.muted, CircleShape),
        )
        Text(
            item.obj.metadata["name"] ?: "Объект",
            style = PointType.body.copy(fontSize = PointType.small.fontSize),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Подсказка внизу дока: чем ещё можно дать объект, когда телефона под рукой нет. */
@Composable
private fun DropHint() {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, PointColors.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(DOCK_HINT, style = PointType.small.copy(color = PointColors.muted), textAlign = TextAlign.Center)
        Text(
            "Ctrl+Shift+V",
            style = PointType.mono,
            modifier = Modifier
                .border(1.dp, PointColors.border, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Text("взять то, что в буфере", style = PointType.small, textAlign = TextAlign.Center)
        Spacer(Modifier.height(1.dp))
    }
}

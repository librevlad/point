package com.point

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.point.core.model.ObjectKind
import com.point.core.ui.GlassIcons
import com.point.core.ui.PointPalette
import com.point.core.ui.kindIcon
import com.point.core.ui.kindLabel
import com.point.core.ui.theme.PointText
import com.point.core.ui.theme.PointTheme

/**
 * Путь вещи пилюлями под её именем (макет 19.10.2026, «история объекта»): откуда пришла и во
 * что превратилась — «Изображение › Текст › PDF». Текущая вещь подсвечена цветом Point, к
 * прежней можно вернуться касанием.
 */
@Composable
fun TimelineStrip(
    path: List<PathStep>,
    onNode: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (path.size < 2) return
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        path.forEachIndexed { index, step ->
            if (index > 0) {
                Icon(
                    imageVector = GlassIcons.ChevronRight,
                    contentDescription = step.via,
                    tint = PointPalette.text.copy(alpha = 0.32f),
                    modifier = Modifier.size(12.dp),
                )
            }
            val current = index == path.lastIndex
            TimelineNode(kind = step.kind, current = current, onClick = { if (!current) onNode(index) })
        }
    }
}

@Composable
private fun TimelineNode(kind: ObjectKind, current: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(if (current) PointPalette.violet.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.08f))
            .then(if (current) Modifier.border(1.dp, PointPalette.violet.copy(alpha = 0.55f), CircleShape) else Modifier)
            .clickable(enabled = !current, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = kindIcon(kind),
            contentDescription = null,
            tint = if (current) PointPalette.text else PointPalette.text.copy(alpha = 0.85f),
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = kindLabel(kind),
            style = PointText.note.copy(
                color = if (current) PointPalette.text else PointPalette.text.copy(alpha = 0.85f),
                fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
            ),
            maxLines = 1,
        )
    }
}

@Preview(name = "Путь вещи · Фото → Текст → PDF", showBackground = true, backgroundColor = 0xFF2B2531)
@Composable
private fun PreviewTimeline() = PointTheme {
    TimelineStrip(
        path = listOf(
            PathStep(ObjectKind.IMAGE, null),
            PathStep(ObjectKind.TEXT, "Распознать текст"),
            PathStep(ObjectKind.PDF, "В PDF"),
        ),
        onNode = {},
    )
}

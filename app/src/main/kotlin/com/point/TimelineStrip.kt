package com.point

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.point.core.model.ObjectKind
import com.point.core.ui.kindIcon
import com.point.core.ui.kindLabel
import com.point.core.ui.theme.PointTheme

/**
 * The Object Timeline (#114): the flow's journey — «Фото → Текст → PDF» — as a strip of
 * tappable nodes above the object. Visible only once a transformation happened (≥2 steps);
 * tapping an earlier node jumps straight back to that object. This is the philosophy made
 * visible: not screens on a stack, an object travelling through states.
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
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        path.forEachIndexed { index, step ->
            if (index > 0) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = step.via,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            val current = index == path.lastIndex
            TimelineNode(kind = step.kind, current = current, onClick = { if (!current) onNode(index) })
        }
        Text(
            text = kindLabel(path.last().kind),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun TimelineNode(kind: ObjectKind, current: Boolean, onClick: () -> Unit) {
    val bg =
        if (current) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant
    val tint =
        if (current) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = !current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = kindIcon(kind),
            contentDescription = kind.name,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Preview(name = "Timeline · Фото → Текст → PDF", showBackground = true)
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

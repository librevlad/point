package com.point.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// Тона и скругления — из общей палитры (#851): у ПК те же самые.
internal val RowTop = PortalTones.rowTop
internal val RowBottom = PortalTones.rowBottom
internal val PlateBase = PortalTones.plateBase
internal val TopHighlight = PortalTones.topHighlight
private val PrimaryStart = PointPalette.violet
private val PrimaryEnd = PointPalette.blue

val PortalColumnWidth = 340.dp

fun Modifier.portalCard(
    shape: Shape = PortalCardShape,
    elevation: Dp = 6.dp,
    accent: Color? = null,
): Modifier = this
    .then(
        if (elevation > 0.dp) {
            Modifier.shadow(elevation, shape, ambientColor = Color.Black, spotColor = Color.Black)
        } else {
            Modifier
        },
    )
    .clip(shape)
    .background(Brush.verticalGradient(listOf(RowTop, RowBottom)))
    .then(
        if (accent != null) {
            Modifier.background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent)))
        } else {
            Modifier
        },
    )
    .border(1.dp, Brush.verticalGradient(listOf(TopHighlight, Color.Transparent)), shape)

fun Modifier.portalPrimary(
    shape: Shape = PortalCardShape,

    // Главное действие — первая строка списка, а не отдельная кнопка над ним (#879).
    // Тень в 20 dp делала из него баннер: он читался как призыв к действию из лендинга,
    // а не как часть общей системы действий.
    elevation: Dp = 10.dp,
): Modifier = this
    .then(
        if (elevation > 0.dp) {
            Modifier.shadow(elevation, shape, ambientColor = PrimaryStart, spotColor = PrimaryStart)
        } else {
            Modifier
        },
    )
    .clip(shape)
    .background(Brush.horizontalGradient(listOf(PrimaryStart, PrimaryEnd)))

@Composable
fun PortalPlate(
    accent: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    image: ImageBitmap? = null,
    onGlass: Boolean = false,
    ring: Color? = null,
    size: Dp = 46.dp,
    shape: Shape = PortalPlateShape,
) {
    val plate = modifier
        .size(size)
        .clip(shape)
        .then(
            if (onGlass) {
                Modifier
                    .background(Color.White.copy(alpha = 0.18f))
                    .border(1.dp, Color.White.copy(alpha = 0.30f), shape)
            } else {
                Modifier
                    .background(PlateBase)
                    .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.34f), Color.Transparent)))
                    .border(1.dp, ring ?: accent.copy(alpha = 0.30f), shape)
            },
        )
    Box(modifier = plate, contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(bitmap = image, contentDescription = null, modifier = Modifier.size(size * 26f / 46f))
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (onGlass) Color.White else accent,
                modifier = Modifier.size(size / 2),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PortalRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    image: ImageBitmap? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    primary: Boolean = false,
    enabled: Boolean = true,
    ring: Color? = null,
    chevron: Boolean = true,
    surface: Boolean = true,

    subtitleMaxLines: Int = 2,

    /** Место в очереди перед именем: метка, а не часть названия (#911). */
    place: Int = 0,
    appearIndex: Int = 0,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val motion = rememberMotionEnabled()
    val presence = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (motion) {
            delay(appearIndex * 40L)
            presence.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }

    val shape = PortalCardShape
    val base = modifier
        .fillMaxWidth()
        .graphicsLayer {
            alpha = presence.value
            translationY = (1f - presence.value) * 10.dp.toPx()
        }
    val body = when {
        primary -> base.portalPrimary(shape)
        surface -> base.portalCard(shape)
        else -> base
    }

    val labelColor = if (primary) Color.White else MaterialTheme.colorScheme.onSurface
    val subColor = if (primary) Color.White.copy(alpha = 0.80f) else MaterialTheme.colorScheme.onSurfaceVariant
    val chevronColor = if (primary) {
        Color.White.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }

    Box(
        modifier = body.combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (icon != null || image != null) {
                PortalPlate(accent = accent, icon = icon, image = image, onGlass = primary, ring = ring)
            }
            if (place > 0) {
                Text(
                    text = "%02d".format(place),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
                    color = labelColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subColor,
                        maxLines = subtitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                trailing != null -> trailing()
                chevron -> Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = chevronColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
fun PortalDoor(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Column(
        modifier = modifier
            .clip(PortalPlateShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        PortalPlate(accent = accent, icon = icon, size = 40.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
/**
 * Заголовок группы — свой уровень типографики (#879). Раньше он был набран так же тихо,
 * как вторичный текст внутри строк, и терялся между ними: список читался как набор
 * разрозненных карточек, а не как «заголовок → однородные строки».
 */
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.3.sp,
        color = color,
        modifier = modifier,
    )
}

@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = horizontalAlignment,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = if (horizontalAlignment == Alignment.CenterHorizontally) TextAlign.Center else null,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (horizontalAlignment == Alignment.CenterHorizontally) TextAlign.Center else null,
            )
        }
    }
}

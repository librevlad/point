package com.point.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import com.point.core.ui.PointPalette
import com.point.core.ui.PortalCardShape
import com.point.core.ui.PortalPlateShape
import com.point.core.ui.PortalTones
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * Поверхности телефона на ПК (порт `core/ui/PortalSurfaces.kt`).
 *
 * Одно и то же действие выглядело двумя разными продуктами: на телефоне — карточка со
 * скруглением 18, вертикальным градиентом, светлой кромкой сверху и плашкой-иконкой 46;
 * на ПК — плоская строка со скруглением 14, точкой 8 и стрелкой-текстом. Общего модуля у
 * Android-UI и Compose Desktop нет, поэтому токены переносятся, а не переиспользуются.
 * Источник правды — телефонный файл: расходиться им нельзя.
 */

// Тона и скругления — из общей палитры (#851): те же самые, что у телефона.
private val RowTop = PortalTones.rowTop
private val RowBottom = PortalTones.rowBottom
private val PlateBase = PortalTones.plateBase
private val TopHighlight = PortalTones.topHighlight
private val PrimaryStart = PointPalette.violet
private val PrimaryEnd = PointPalette.blue

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
    elevation: Dp = 20.dp,
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

/** Плашка действия: цвет действия светится изнутри, а не стоит точкой рядом. */
@Composable
fun PortalPlate(
    accent: Color,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    onGlass: Boolean = false,
    size: Dp = 40.dp,
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
                    .border(1.dp, accent.copy(alpha = 0.30f), shape)
            },
        )

    Box(plate, contentAlignment = Alignment.Center) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (onGlass) Color.White else accent,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/**
 * Строка действия — та же, что на телефоне (`core/ui` PortalRow).
 *
 * Обещание живёт под названием второй строкой, а не хвостом справа: человек читает сверху
 * вниз, и «что будет, если нажать» стоит там же, где он привык его видеть.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun PortalRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color = PointColors.violet,
    primary: Boolean = false,
    enabled: Boolean = true,
    appearIndex: Int = 0,
    chevron: Boolean = true,
    subtitleMaxLines: Int = 2,
    trailing: (@Composable () -> Unit)? = null,
) {
    val presence = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(appearIndex * 40L)
        presence.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow))
    }

    val base = modifier
        .fillMaxWidth()
        .graphicsLayer {
            alpha = presence.value
            translationY = (1f - presence.value) * 10.dp.toPx()
        }
    val body = if (primary) base.portalPrimary() else base.portalCard()

    val labelColor = if (primary) Color.White else PointColors.text
    val subColor = if (primary) Color.White.copy(alpha = 0.80f) else PointColors.muted

    // Наведение — то, чего нет и не может быть на телефоне (#879): курсор отвечает «сюда
    // попаду» до нажатия. Модель строки при этом та же, меняется только подсветка.
    val hovered = remember { mutableStateOf(false) }
    val lit = if (hovered.value && !primary) {
        body.border(1.dp, PointColors.violet.copy(alpha = 0.55f), PortalCardShape)
    } else {
        body
    }

    Box(
        lit
            .onPointerEvent(PointerEventType.Enter) { hovered.value = true }
            .onPointerEvent(PointerEventType.Exit) { hovered.value = false }
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            // Плотнее телефона: мышь точнее пальца, а в окне вертикаль дороже (#879).
            modifier = Modifier.padding(horizontal = 12.dp, vertical = if (primary) 11.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            // Плашка нужна действию, а не строке настроек: пустой квадрат без значка —
            // обещание картинки, которой нет. На телефоне строки настроек стоят без неё (#886).
            if (icon != null) PortalPlate(accent = accent, icon = icon, onGlass = primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = PointType.body.copy(color = labelColor),
                    fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = PointType.small.copy(color = subColor),
                        maxLines = subtitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                trailing()
            } else if (chevron) {
                // Шеврон говорит, что за строкой продолжение, а не мгновенный результат.
                // Приглушён: повторённый десять раз в полную силу он становится шумом (#879).
                Text(
                    "›",
                    style = PointType.body.copy(
                        color = if (primary) Color.White.copy(alpha = 0.7f) else PointColors.muted.copy(alpha = 0.45f),
                    ),
                )
            }
        }
    }
}

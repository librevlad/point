package com.point.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.point.core.ui.actionTint

/*
 * Строка и плашка, которыми пишут настройки, аккаунт и действия.
 *
 * Прежде это был порт телефонных поверхностей (`core/ui/PortalSurfaces.kt`): карточка со
 * скруглением 18, вертикальным градиентом и цветной плашкой 40. Макет 19.10.2026 даёт компьютеру
 * своё стекло, и строка рисуется им (`GlassRow`); имя и параметры остались прежними, чтобы экраны,
 * которые ими пишут, не переписывались ради формы.
 */

/** Плашка со знаком: цвет действия смягчён к пастели, знак — светлой линией. */
@Composable
fun PortalPlate(
    accent: Color,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    onGlass: Boolean = false,
    size: Dp = 32.dp,
) {
    Plate(
        icon = icon,
        modifier = modifier,
        tint = if (onGlass) null else actionTint(accent),
        size = size,
        iconSize = size * 0.48f,
        onLight = onGlass,
    )
}

/**
 * Строка действия или раздела — та же модель, что на телефоне (`core/ui` PortalRow): обещание
 * живёт второй строкой под названием, место исполнения — меткой справа (#1384).
 *
 * Появляется строка мягко, со сдвигом по номеру: список собирается на глазах, а не вспыхивает.
 */
@Composable
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

    /** Место в очереди перед именем: метка, а не часть названия (#911). */
    place: Int = 0,
    chevron: Boolean = true,
    subtitleMaxLines: Int = 2,

    /** Где действие исполнится: состояние строки, а не обещание действия (#1384). */
    mark: String? = null,
    dim: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val presence = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(appearIndex * 35L)
        presence.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow))
    }
    GlassRow(
        title = title,
        lead = if (place > 0) "%02d".format(place) else null,
        onClick = if (enabled) onClick else null,
        subtitle = subtitle,
        icon = icon,
        tint = actionTint(accent),
        primary = primary,
        dim = dim,
        subtitleLines = subtitleMaxLines,
        modifier = modifier.graphicsLayer {
            alpha = presence.value
            translationY = (1f - presence.value) * 8.dp.toPx()
        },
    ) {
        if (mark != null) PlaceMark(mark, onLight = primary)
        when {
            trailing != null -> trailing()
            chevron -> Chevron(onLight = primary)
        }
    }
}

/** Подпись под строкой, когда строки нет — пояснение раздела, тихий текст. */
@Composable
fun QuietNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = PointType.note, modifier = modifier)
}

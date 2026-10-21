package com.point.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.point.core.model.tr
import com.point.core.ui.GlassIcons
import com.point.core.ui.GlassTone
import com.point.core.ui.drawWallpaper
import com.point.core.ui.glassEdge
import com.point.core.ui.lightSurface

/*
 * Стекло Point на компьютере (макет владельца «Point · концепт в стиле Gestalt», 19.10.2026).
 *
 * Три вида стекла: окно (тёмное, сквозь него видны размытые пятна обоев), панель (группа
 * действий, блок содержимого — чуть светлее окна) и строка (светлеет под курсором). Главное
 * действие — светлая строка с тёмным текстом: оно стоит первой строкой списка, а не баннером.
 *
 * Обои, кромка и светлая строка — общие с телефоном (`core/ui/src/shared`, `GlassSurfaces.kt`):
 * здесь только то, что есть у окна и нет у телефона, — курсор, подсказка при наведении, шапка окна.
 */


/**
 * Окно целиком: обои, стекло, кромка — и линия цвета компьютера сверху.
 *
 * Линия говорит, где живёт окно: компьютер в Point синий, телефон зелёный (точка «телефон
 * рядом» в шапке). Так макет переносит из Gestalt «линию узла»: цвет — это устройство.
 */
@Composable
fun GlassWindow(
    modifier: Modifier = Modifier,
    tone: GlassTone = GlassTone.WINDOW,
    shape: Shape = RoundedCornerShape(16.dp),
    line: Color? = PointColors.blue,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .drawBehind {
                drawWallpaper(tone)
                if (line != null) drawRect(line, size = Size(size.width, 3.dp.toPx()))
            }
            .border(1.dp, glassEdge(0.27f, 0.13f), shape),
        content = content,
    )
}

/** Панель: группа действий, блок содержимого. */
fun Modifier.glassPanel(shape: Shape = RoundedCornerShape(18.dp)): Modifier = this
    .clip(shape)
    .background(PointColors.surface)
    .border(1.dp, glassEdge(0.2f, 0.09f), shape)

/** Нажимаемое отвечает курсором-рукой: на компьютере это обещание «сюда можно». */
fun Modifier.handCursor(): Modifier = pointerHoverIcon(PointerIcon.Hand)

/**
 * Круглая плашка со знаком: у строки списка, у действия, у пункта меню.
 *
 * [tint] — пастельный тон (вид объекта, цвет действия); без него плашка нейтрального стекла.
 */
@Composable
fun Plate(
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    size: Dp = 32.dp,
    iconSize: Dp = 15.dp,
    onLight: Boolean = false,
    contentDescription: String? = null,
) {
    val fill = when {
        onLight -> PointColors.ink.copy(alpha = 0.09f)
        tint != null -> tint.copy(alpha = 0.26f)
        else -> Color.White.copy(alpha = 0.09f)
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .then(
                if (onLight) {
                    Modifier
                } else {
                    Modifier.border(1.dp, Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), 0.35f to Color.Transparent), CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (onLight) PointColors.ink else PointColors.text,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** Заголовок группы: прописными, с разрядкой, и тонкая линия до края. */
@Composable
fun GroupHeader(text: String, modifier: Modifier = Modifier, top: Dp = 14.dp) {
    Row(
        modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = top, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text.uppercase(), style = PointType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.weight(1f).height(1.dp).background(PointColors.hairline))
    }
}

/** Шапка панели со своим «назад»: настройки и их разделы. Имя экрана сказано здесь один раз. */
@Composable
fun PaneHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoundButton(GlassIcons.ChevronLeft, tr("Назад"), onBack)
        Text(title, style = PointType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Точка состояния: телефон рядом, найденное, идущая работа. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** Шеврон строки: за ней продолжение, а не мгновенный результат. Приглушён, чтобы не шуметь. */
@Composable
fun Chevron(onLight: Boolean = false) {
    Icon(
        GlassIcons.ChevronRight,
        contentDescription = null,
        tint = if (onLight) PointColors.ink.copy(alpha = 0.45f) else PointColors.text.copy(alpha = 0.3f),
        modifier = Modifier.size(15.dp),
    )
}

/** Метка места исполнения справа в строке: «на телефоне». */
@Composable
fun PlaceMark(text: String, onLight: Boolean = false) {
    Text(
        text,
        style = PointType.note.copy(color = if (onLight) PointColors.ink.copy(alpha = 0.7f) else PointColors.muted),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (onLight) PointColors.ink.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/**
 * Строка стекла — одна форма на объект в списке, действие и строку настроек.
 *
 * [primary] — главное действие: светлая строка, тёмный текст. [selected] — открытый сейчас
 * объект. [dim] — недоступное: строка на месте, гаснут знак и название, причина — второй строкой.
 */
@Composable
fun GlassRow(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tint: Color? = null,
    primary: Boolean = false,
    selected: Boolean = false,
    dim: Boolean = false,
    minHeight: Dp = 48.dp,
    subtitleLines: Int = 1,
    iconDescription: String? = null,

    /** Метка перед названием — место в очереди (#911): своим узлом, а не частью названия. */
    lead: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(14.dp)
    val body = when {
        primary -> Modifier.lightSurface(shape)
        selected -> Modifier.clip(shape)
            .background(PointColors.violet.copy(alpha = 0.2f))
            .border(1.dp, PointColors.violet.copy(alpha = 0.4f), shape)
        hovered && onClick != null -> Modifier.clip(shape).background(Color.White.copy(alpha = 0.08f))
        else -> Modifier.clip(shape)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (primary) minHeight + 4.dp else minHeight)
            .then(body)
            .hoverable(interaction)
            .then(
                if (onClick != null) {
                    Modifier.handCursor().clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Plate(icon = icon, tint = if (dim) null else tint, onLight = primary, contentDescription = iconDescription)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lead?.let {
                    Text(it, style = PointType.mono.copy(color = if (primary) PointColors.ink.copy(alpha = 0.55f) else PointColors.faint))
                }
                Text(
                    title,
                    style = PointType.rowTitle.copy(
                        color = when {
                            primary -> PointColors.ink
                            dim -> PointColors.muted
                            else -> PointColors.text
                        },
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = PointType.note.copy(color = if (primary) PointColors.ink.copy(alpha = 0.6f) else PointColors.muted),
                    maxLines = subtitleLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        trailing?.invoke(this)
    }
}

/** Кнопка второго плана: «Скопировать», «Отменить», «Выйти». */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    small: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val height = if (small) 28.dp else 34.dp
    val shape = RoundedCornerShape(height / 2)
    Row(
        modifier
            .height(height)
            .clip(shape)
            .background(Color.White.copy(alpha = if (hovered && enabled) 0.15f else 0.09f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .hoverable(interaction)
            .then(if (enabled) Modifier.handCursor().clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = if (small) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = PointColors.text, modifier = Modifier.size(if (small) 12.dp else 14.dp))
        }
        Text(
            text,
            style = (if (small) PointType.note else PointType.small).copy(
                color = if (enabled) PointColors.text else PointColors.muted,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

/** Светлая кнопка главного шага вне списка: «Войти», «Продолжить». */
@Composable
fun LightButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(44.dp)
            .lightSurface(RoundedCornerShape(22.dp))
            .handCursor()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PointType.rowTitle.copy(color = PointColors.ink), maxLines = 1)
    }
}

/**
 * Круглая кнопка шапки окна: настройки, свернуть, развернуть, закрыть.
 *
 * Знак без слова, поэтому слово приходит при наведении: значок, который человек принял за
 * лампочку, назывался «Настройки» только для экранного диктора (#462 — безымянных значков нет).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RoundButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    warm: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val base = if (warm) PointColors.close.copy(alpha = if (hovered) 0.55f else 0.32f) else Color.White.copy(alpha = if (hovered) 0.16f else 0.09f)
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Text(
                contentDescription,
                style = PointType.note.copy(color = PointColors.text),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xF0302936))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        },
        delayMillis = 400,
        modifier = modifier,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(base)
                .hoverable(interaction)
                .handCursor()
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = PointColors.text, modifier = Modifier.size(13.dp))
        }
    }
}


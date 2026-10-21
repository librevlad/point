package com.point.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.point.core.ui.GlassIcons
import com.point.core.ui.lightSurface

/** Пункт меню способов взять объект: знак, имя, пояснение и клавиша. */
data class MenuItem(
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val note: String? = null,
    val keys: String? = null,
)

/**
 * «Новый объект» — раздельная кнопка (макет 19.10.2026).
 *
 * Главная часть делает самый частый способ — выбрать файл, стрелка открывает остальные: из
 * буфера, снимок экрана, принять по ссылке. Прежде эти способы стояли хвостом списка «Недавнее»
 * разделом «Создать объект» и читались как его продолжение.
 */
@Composable
fun SplitButton(
    title: String,
    note: String,
    onMain: () -> Unit,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    items: List<MenuItem>,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(modifier) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).lightSurface(shape)) {
            Row(
                Modifier.weight(1f).handCursor().clickable(onClick = onMain).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Plate(GlassIcons.Plus, onLight = true, iconSize = 16.dp)
                Column(Modifier.weight(1f)) {
                    Text(title, style = PointType.rowTitle.copy(color = PointColors.ink), maxLines = 1)
                    Text(note, style = PointType.note.copy(color = PointColors.ink.copy(alpha = 0.6f)), maxLines = 1)
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(PointColors.ink.copy(alpha = 0.12f)))
            Box(
                Modifier.width(44.dp).fillMaxHeight()
                    .background(if (menuOpen) PointColors.ink.copy(alpha = 0.1f) else Color.Transparent)
                    .handCursor()
                    .clickable { onMenu(!menuOpen) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (menuOpen) GlassIcons.ChevronUp else GlassIcons.ChevronDown,
                    contentDescription = if (menuOpen) "Свернуть способы" else "Другие способы взять объект",
                    tint = PointColors.ink,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (menuOpen) {
            GlassMenu(items = items, onDismiss = { onMenu(false) })
        }
    }
}

/** Меню под кнопкой, прижатое к её правому краю. */
private object BelowRight : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = (anchorBounds.right - popupContentSize.width).coerceAtLeast(0),
        y = (anchorBounds.bottom + 8).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
}

@Composable
private fun GlassMenu(items: List<MenuItem>, onDismiss: () -> Unit) {
    Popup(popupPositionProvider = BelowRight, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier.width(315.dp)
                .clip(shape)
                .background(Color(0xF0282230))
                .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
                .padding(6.dp),
        ) {
            items.forEach { item ->
                MenuRow(item) {
                    onDismiss()
                    item.onClick()
                }
            }
        }
    }
}

@Composable
private fun MenuRow(item: MenuItem, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (hovered) Color.White.copy(alpha = 0.1f) else Color.Transparent)
            .hoverable(interaction)
            .handCursor()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Plate(item.icon, tint = PointColors.blue, size = 28.dp, iconSize = 14.dp)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(item.title, style = PointType.body, maxLines = 1)
            item.note?.let {
                Text(" · $it", style = PointType.note, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item.keys?.let { Text(it, style = PointType.mono) }
    }
}

/** Переключатель: состояние сказано словом под названием и видно справа. */
@Composable
fun GlassToggle(on: Boolean) {
    Box(
        Modifier.width(40.dp).height(23.dp)
            .clip(CircleShape)
            .background(if (on) PointColors.violet else Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier.padding(horizontal = 3.dp).size(17.dp).clip(CircleShape)
                .background(if (on) PointColors.ink else PointColors.text.copy(alpha = 0.7f)),
        )
    }
}

/** Поле ввода на стекле: подпись сверху, само поле — тёмная капсула. */
@Composable
fun GlassField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    secret: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(hint, style = PointType.note)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = PointType.body,
            cursorBrush = SolidColor(PointColors.violet),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** Поиск в шапке окна: объект в «Недавнем» или действие открытого объекта. */
@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(
        modifier.height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(GlassIcons.Search, contentDescription = null, tint = PointColors.muted, modifier = Modifier.size(14.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(placeholder, style = PointType.small, maxLines = 1)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = PointType.small.copy(color = PointColors.text),
                cursorBrush = SolidColor(PointColors.violet),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                GlassIcons.Close,
                contentDescription = "Очистить поиск",
                tint = PointColors.muted,
                modifier = Modifier.size(12.dp).handCursor().clickable { onQuery("") },
            )
        }
    }
}

/** Сообщение окна: капсула стекла внизу, гаснет сама или по щелчку. */
@Composable
fun MessageBar(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        text,
        style = PointType.small.copy(color = PointColors.text),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(shape)
            .background(Color(0xF0302936))
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
            .handCursor()
            .clickable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/** Сегмент полосы: подпись, знак перед ней; текущий подсвечен, сорвавшийся притушен. */
data class Segment(
    val text: String,
    val current: Boolean = false,
    val faded: Boolean = false,
    val icon: ImageVector? = null,
)

/**
 * Полоса в несколько сегментов — путь объекта (макет 19.10.2026): откуда пришёл, он сам, что с
 * ним делали. Щелчок по полосе — [onClick], если он есть; у самих сегментов своей двери нет.
 */
@Composable
fun SegmentBar(segments: List<Segment>, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.height(32.dp).clip(shape).background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .let { if (onClick != null) it.handCursor().clickable(onClick = onClick) else it },
    ) {
        segments.forEachIndexed { i, segment ->
            Row(
                Modifier.fillMaxHeight()
                    .background(if (segment.current) PointColors.violet.copy(alpha = 0.28f) else Color.Transparent)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                val ink = when {
                    segment.current -> PointColors.text
                    segment.faded -> PointColors.faint
                    else -> PointColors.soft
                }
                segment.icon?.let { Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(12.dp)) }
                Text(
                    segment.text,
                    style = PointType.small.copy(
                        color = ink,
                        fontWeight = if (segment.current) androidx.compose.ui.text.font.FontWeight.Medium else null,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (i < segments.lastIndex) Spacer(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.08f)))
        }
    }
}

/**
 * Вопрос окна — стеклом и словами последствий (P11): что случится по «да» — светлой кнопкой,
 * отказ — рядом и тише.
 */
@Composable
fun GlassDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String = "Не сейчас",
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = PointType.title) },
        text = { Text(text, style = PointType.body.copy(color = PointColors.soft)) },
        confirmButton = { LightButton(confirm, onConfirm) },
        dismissButton = { PillButton(dismiss, onDismiss) },
        containerColor = Color(0xF7282230),
        shape = RoundedCornerShape(20.dp),
    )
}

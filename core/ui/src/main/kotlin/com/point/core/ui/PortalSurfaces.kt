package com.point.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.point.core.ui.theme.PointText
import kotlinx.coroutines.delay

/*
 * Стекло телефона (макет владельца «Point · концепт в стиле Gestalt», 19.10.2026; решение
 * владельца 20.10.2026 — «ставь дизайн и на мобильное приложение»).
 *
 * Экран — сами обои: тёплая сливовая земля и мягкие пятна света. На обоях лежат группы — панели
 * стекла со скруглением 26, — а в группах строки: круглая пастельная плашка со знаком линией,
 * имя, обещание второй строкой, шеврон. Главное действие — светлая строка с тёмным текстом:
 * первой строкой своей группы, а не баннером над списком (#879).
 *
 * Имена прежних поверхностей (`PortalRow`, `portalCard`, `PortalPlate`) остались: экраны,
 * которые ими пишут, не переписываются ради формы, а форма у всех одна.
 */

/** Ширина колонки строк, когда экран шире телефона: строка не растягивается во всю ширину. */
val PortalColumnWidth = 380.dp

/** Скругление группы — панели стекла, в которой лежат строки. */
val GlassGroupShape = RoundedCornerShape(26.dp)

/** Плашка у строки — от левого края строки до её текста: так ложится линия между строками. */
private val PLATE_INSET = 12.dp + 36.dp + 14.dp

/**
 * Панель стекла на обоях: группа строк, блок содержимого, вопрос на месте.
 *
 * Сверху светлее — на стекло падает свет; кромка тонкая, у верхнего края ярче. Тени у стекла
 * нет: полупрозрачная панель с тенью темнеет изнутри, а в макете она светлая.
 */
fun Modifier.portalCard(
    shape: Shape = GlassGroupShape,

    /** Тон вещи на краю панели: исход действия, найденное. */
    accent: Color? = null,
): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.05f))))
    .then(
        if (accent != null) {
            Modifier.background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent)))
        } else {
            Modifier
        },
    )
    .border(1.dp, glassEdge(0.24f, 0.13f), shape)

/** Светлая строка главного действия — та же, что у окна компьютера (`lightSurface`). */
fun Modifier.portalPrimary(shape: Shape = PortalCardShape): Modifier = lightSurface(shape)

/**
 * Группа строк: панель стекла, строки внутри без своих карточек. Между строками —
 * [GroupSeam].
 */
@Composable
fun GlassGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().portalCard().padding(6.dp), content = content)
}

/** Тонкая линия между строками группы — от текста, а не от края: плашки стоят столбиком. */
@Composable
fun GroupSeam(start: Dp = PLATE_INSET) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = start, end = 14.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.07f)),
    )
}

/**
 * Круглая плашка со знаком: у строки, у найденного, у устройства.
 *
 * Цвет действия на стекле смягчён к пастели (`actionTint`), знак — светлой линией. На светлой
 * главной строке ([onGlass]) плашка тёмная и прозрачная, знак — цветом текста строки. [ring] —
 * кромка цвета: так отмечены действия с AI и устройства круга.
 */
@Composable
fun PortalPlate(
    accent: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    image: ImageBitmap? = null,
    onGlass: Boolean = false,
    ring: Color? = null,
    size: Dp = 36.dp,
    shape: Shape = CircleShape,
    tint: Boolean = true,
) {
    val fill = when {
        onGlass -> PointPalette.ink.copy(alpha = 0.09f)
        tint -> actionTint(accent).copy(alpha = 0.26f)
        else -> Color.White.copy(alpha = 0.09f)
    }
    val plate = modifier
        .size(size)
        .clip(shape)
        .background(fill)
        .then(
            when {
                ring != null -> Modifier.border(1.5.dp, ring, shape)
                onGlass -> Modifier
                else -> Modifier.border(1.dp, Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), 0.35f to Color.Transparent), shape)
            },
        )
    Box(modifier = plate, contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(bitmap = image, contentDescription = null, modifier = Modifier.size(size * 0.6f))
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (onGlass) PointPalette.ink else PointPalette.text,
                modifier = Modifier.size(size / 2),
            )
        }
    }
}

/**
 * Строка стекла — одна форма на действие, объект в списке и строку настроек.
 *
 * [primary] — главное действие: светлая строка, тёмный текст. [surface] — строка стоит сама по
 * себе и сама себе группа; внутри [GlassGroup] она плоская.
 */
@Composable
fun PortalRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    image: ImageBitmap? = null,
    accent: Color = PointPalette.violet,
    primary: Boolean = false,
    enabled: Boolean = true,
    ring: Color? = null,
    chevron: Boolean = true,
    surface: Boolean = true,

    subtitleMaxLines: Int = 2,

    /** Место в очереди перед именем: метка, а не часть названия (#911). */
    place: Int = 0,
    appearIndex: Int = 0,
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

    val outer = modifier
        .fillMaxWidth()
        .graphicsLayer {
            alpha = presence.value
            translationY = (1f - presence.value) * 10.dp.toPx()
        }
        .then(if (surface) Modifier.portalCard().padding(6.dp) else Modifier)

    val row = Modifier
        .fillMaxWidth()
        .heightIn(min = 58.dp)
        .then(if (primary) Modifier.portalPrimary(PortalCardShape) else Modifier.clip(PortalCardShape))
        .clickable(enabled = enabled, onClick = onClick)

    Box(outer) {
        Row(
            modifier = row.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (icon != null || image != null) {
                PortalPlate(accent = accent, icon = icon, image = image, onGlass = primary, ring = ring)
            }
            if (place > 0) {
                Text(
                    text = "%02d".format(place),
                    style = PointText.mono.copy(color = if (primary) PointPalette.ink.copy(alpha = 0.55f) else PointPalette.faint),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = PointText.rowTitle.copy(
                        color = when {
                            primary -> PointPalette.ink
                            enabled -> PointPalette.text
                            else -> PointPalette.muted
                        },
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = PointText.note.copy(color = if (primary) PointPalette.ink.copy(alpha = 0.6f) else PointPalette.muted),
                        maxLines = subtitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            when {
                trailing != null -> trailing()
                chevron -> Chevron(onLight = primary)
            }
        }
    }
}

/** Шеврон строки: за ней продолжение. Приглушён, чтобы не шуметь. */
@Composable
fun Chevron(onLight: Boolean = false) {
    Icon(
        GlassIcons.ChevronRight,
        contentDescription = null,
        tint = if (onLight) PointPalette.ink.copy(alpha = 0.45f) else PointPalette.text.copy(alpha = 0.3f),
        modifier = Modifier.size(16.dp),
    )
}

/**
 * Дверь со знаком и словом — пилюлей стекла: «Настройки» в шапке «Недавнего».
 *
 * Знак без слова на телефоне не ставится (#462): подсказки при наведении у пальца нет, и знак,
 * который человек принял не за то, так и остался бы непонятым.
 */
@Composable
fun PortalDoor(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") accent: Color = PointPalette.violet,
) {
    GlassPill(label = label, onClick = onClick, icon = icon, modifier = modifier)
}

/**
 * Пилюля стекла со знаком и словом: дверь в шапке, выход с экрана вещи.
 *
 * Высота — та же, что у круглой кнопки шапки (40): пилюля и круг стоят в одной строке.
 */
@Composable
fun GlassPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.09f))
            .border(1.dp, Brush.verticalGradient(0f to Color.White.copy(alpha = 0.18f), 0.4f to Color.Transparent), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = if (icon != null) 11.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = PointPalette.text, modifier = Modifier.size(18.dp))
        }
        Text(
            label,
            style = PointText.rowTitle.copy(fontSize = PointText.rowTitle.fontSize * 0.93f, color = if (enabled) PointPalette.text else PointPalette.muted),
            maxLines = 1,
        )
    }
}

/**
 * Круглая кнопка шапки: «назад» у экрана со своим заголовком.
 *
 * Знак без слова — только там, где слово стоит рядом: заголовок экрана говорит, где человек, а
 * [contentDescription] — куда ведёт кнопка.
 */
@Composable
fun RoundButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.09f))
            .border(1.dp, Brush.verticalGradient(0f to Color.White.copy(alpha = 0.18f), 0.4f to Color.Transparent), CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = PointPalette.text, modifier = Modifier.size(18.dp))
    }
}

/**
 * Шапка экрана со своим «назад»: круглая кнопка и имя экрана рядом — «‹ Настройки», «‹ Мои
 * устройства». Имя экрана сказано здесь один раз.
 */
@Composable
fun ScreenTopBar(
    title: String,
    onBack: () -> Unit,
    backLabel: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoundButton(GlassIcons.ChevronLeft, backLabel, onBack)
        Text(title, style = PointText.screenTitle.copy(color = PointPalette.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Светлая кнопка главного шага вне списка: «Войти», «Разрешить», «Готово». */
@Composable
fun LightButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .portalPrimary(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = PointText.rowTitle.copy(fontSize = PointText.rowTitle.fontSize * 1.03f, color = PointPalette.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** Кнопка второго плана во всю ширину: «Отмена», «Открыть страницу входа снова». */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.09f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = PointPalette.text, modifier = Modifier.size(18.dp))
        Text(text, style = PointText.rowTitle.copy(fontSize = PointText.rowTitle.fontSize * 0.93f, color = if (enabled) PointPalette.text else PointPalette.muted), maxLines = 1)
    }
}

/** Маленькая кнопка в строке: «Отключить», «Свернуть». [warm] — тёплым тоном, когда шаг уводит. */
@Composable
fun SmallPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, warm: Boolean = false, enabled: Boolean = true) {
    Box(
        modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.09f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = PointText.note.copy(
                color = when {
                    !enabled -> PointPalette.muted
                    warm -> PointPalette.warmText
                    else -> PointPalette.text
                },
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

/** Поле ввода на стекле: прозрачное, с тонкой кромкой; в фокусе кромка — цвета Point. */
@Composable
fun glassFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = PointPalette.text,
    unfocusedTextColor = PointPalette.text,
    focusedContainerColor = Color.White.copy(alpha = 0.07f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
    focusedBorderColor = PointPalette.violet.copy(alpha = 0.8f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
    cursorColor = PointPalette.violet,
    focusedPlaceholderColor = PointPalette.muted,
    unfocusedPlaceholderColor = PointPalette.muted,
    focusedLabelColor = PointPalette.violet,
    unfocusedLabelColor = PointPalette.muted,
)

/** Скругление поля ввода — то же семейство, что строки стекла. */
val GlassFieldShape = RoundedCornerShape(16.dp)

/** Цвета переключателя на стекле: включённый — фиолетовый Point с тёмной ручкой. */
@Composable
fun glassSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = PointPalette.ink,
    checkedTrackColor = PointPalette.violet.copy(alpha = 0.9f),
    checkedBorderColor = Color.Transparent,
    checkedIconColor = PointPalette.violet,
    uncheckedThumbColor = PointPalette.text.copy(alpha = 0.75f),
    uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
    uncheckedBorderColor = Color.Transparent,
    uncheckedIconColor = PointPalette.ink,
)

/**
 * Заголовок группы — свой уровень типографики (#879): прописными, с разрядкой, над панелью, а не
 * внутри неё. Список читается «заголовок → однородные строки», а не набором карточек.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = PointText.label.color,
) {
    Text(
        text = text.uppercase(),
        style = PointText.label.copy(color = color),
        modifier = modifier,
    )
}

/**
 * Заголовок группы на своём месте: отступ слева как у строк, линия до края — там, где группы
 * идут лентой («Сейчас», «Сегодня», «Вчера»).
 */
@Composable
fun GroupHeader(text: String, modifier: Modifier = Modifier, line: Boolean = false, top: Dp = 18.dp) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = top, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionLabel(text)
        if (line) Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.09f)))
    }
}

@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    val center = horizontalAlignment == Alignment.CenterHorizontally
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = horizontalAlignment,
    ) {
        Text(
            text = title,
            style = PointText.screenTitle.copy(color = PointPalette.text),
            textAlign = if (center) TextAlign.Center else null,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = PointText.subline,
                textAlign = if (center) TextAlign.Center else null,
            )
        }
    }
}

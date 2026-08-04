package com.point.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

/*
 * Из чего сделан экран объекта — вынуто, чтобы из этого же делались остальные экраны (#114).
 *
 * Токены и строка действия родились внутри `ObjectActions`/`OutcomeBanner` приватными, и всё, что
 * писалось позже — выбор источника, ключ, компьютер, приём файла, — дотянуться до них не могло.
 * Каждый такой экран собирался из того, что под рукой: Material-кнопка, крутилка, красный
 * `colorScheme.error`. Снаружи это читается не как «другой экран», а как **другое приложение**.
 *
 * Поэтому здесь лежит ровно то, что уже было на эталонном экране, — без единого нового цвета и без
 * единой новой формы. Эталон от переезда не изменился: `ObjectActions` рисует те же строки теми же
 * значениями, только берёт их отсюда.
 */

// Премиальные токены тёмного неона (дизайн-система, `docs/design-system.png`). Список не имеет
// права выглядеть «дёшево»: поверхность с верхней подсветкой и настоящей глубиной, иконные плиты со
// свечением своего цвета, одна яркая строка-герой.
internal val RowTop = Color(0xFF1A1D25)       // верх мягкого top-lit градиента строки
internal val RowBottom = Color(0xFF121419)    // низ — на волос ниже ПОВЕРХНОСТИ, даёт телу глубину
internal val PlateBase = Color(0xFF1F222B)    // основа иконной плиты под цветным свечением
internal val TopHighlight = Color(0x12FFFFFF) // 7% белого по верхней грани — примета «стекла»
private val PrimaryStart = Color(0xFF7B5CFF)  // АКЦЕНТ1 — начало градиента героя (фиолетовый)
private val PrimaryEnd = Color(0xFF4E7BFF)    // к синему (циан занят кольцом AI)

/**
 * Тёплый конец фирменного градиента — свет того, что не получилось.
 *
 * Жил приватно внутри карточки исхода, и второй экран, которому понадобилось сказать «не вышло»
 * (состояние ключа в настройках, #447), дотянуться до него не мог — взял бы красный
 * `colorScheme.error`, которым Point не говорит нигде. Тот же урок, ради которого затевался #114:
 * пока язык живёт приватно, каждый новый экран обязан заговорить на чужом.
 */
val PortalWarm = Color(0xFFF85938)

/** Скругление карточки и строки действия. */
val PortalCardShape = RoundedCornerShape(18.dp)

/** Скругление иконной плиты внутри строки. */
val PortalPlateShape = RoundedCornerShape(14.dp)

/**
 * Ширина колонки экрана. Карточки понятого, готовности и исхода стоят на экране объекта одной
 * колонкой этой ширины — остальные экраны держат её же, иначе «одна система» распадается на
 * страницы разной ширины.
 */
val PortalColumnWidth = 340.dp

/**
 * Поверхность портала: тёмная карточка с верхней подсветкой и мягкой тенью.
 *
 * Ею сделаны строка действия, карточка исхода и карточка ссылки на экране объекта. [accent] — свет
 * исхода: ложится с той стороны, где стоит знак, и гаснет к тексту, так что карточка окрашена, но
 * читается как поверхность портала, а не как цветной блок.
 */
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

/**
 * Иконная плита строки: плитка со свечением своего цвета и иконкой в нём же.
 *
 * На светящейся строке-герое ([onGlass]) плита белая-стеклянная. [ring] — кольцо АКЦЕНТ2 у
 * действия, которое уходит с устройства; [image] — настоящая иконка чужого приложения.
 */
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

/**
 * Строка портала — то, чем на экране объекта выглядит действие: `[плита] Название ›`.
 *
 * [primary] — «Основное действие» дизайн-системы: градиент АКЦЕНТ1→синий с цветным свечением и
 * белым текстом. Остальные строки тихие: тёмная карточка с глубиной и цветной плитой. Строки
 * всплывают мягким стаггером по [appearIndex]; при выключенной анимации появляются на месте.
 *
 * [subtitle] — вторая строка под названием (адрес компьютера, чем хорош провайдер). На экране
 * объекта её нет: у действия есть только имя, и придумывать ему подпись было бы шумом.
 *
 * [trailing] — то, что стоит справа вместо шеврона, когда у строки есть второе, отдельное желание
 * («сходить за ключом» — не то же, что «выбрать этого»).
 */
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
    /** Сколько строк отдано подписи. Больше двух — когда подпись это **цена выбора**, и обрезать
     *  её значит скрыть половину того, за что человек платит. */
    subtitleMaxLines: Int = 2,
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
    val surface =
        if (primary) {
            base
                .shadow(20.dp, shape, ambientColor = PrimaryStart, spotColor = PrimaryStart)
                .clip(shape)
                .background(Brush.horizontalGradient(listOf(PrimaryStart, PrimaryEnd)))
        } else {
            base.portalCard(shape)
        }

    val labelColor = if (primary) Color.White else MaterialTheme.colorScheme.onSurface
    val subColor = if (primary) Color.White.copy(alpha = 0.80f) else MaterialTheme.colorScheme.onSurfaceVariant
    val chevronColor = if (primary) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = surface.combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = if (primary) 16.dp else 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (icon != null || image != null) {
                PortalPlate(accent = accent, icon = icon, image = image, onGlass = primary, ring = ring)
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
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Лейбл секции — «ИЗВЛЕЧЬ» / «ПРЕВРАТИТЬ» / «ОТПРАВИТЬ» с экрана объекта: тихая разрядка заглавными,
 * которой разделены группы строк. Ею же разделены группы на остальных экранах.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Заголовок экрана: как экран называется и, если есть что сказать, одна строка под ним.
 *
 * Шрифт — характерный `headlineSmall` (Unbounded), тот же, которым Point подписывает себя. Вес
 * решает типографика, а не экран: раньше один экран ставил `Bold` руками, другой не ставил, и
 * одинаковые по смыслу заголовки выходили разными.
 */
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

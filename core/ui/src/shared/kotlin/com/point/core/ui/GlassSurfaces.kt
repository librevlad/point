package com.point.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/*
 * Стекло Point (макет владельца «Point · концепт в стиле Gestalt», 19.10.2026) — то, что у
 * телефона и окна компьютера общее: обои под стеклом и светлая главная строка.
 *
 * Размытия за стеклом Compose не умеет ни на одном из устройств, поэтому обои рисуются внутри
 * самого экрана: земля и мягкие пятна света, как сквозь матовое стекло. Сами пятна и так
 * размыты — размывать за ними нечего.
 */

/**
 * Что лежит на обоях: ничего (экран телефона — сами обои), тёмное окно компьютера или светлая
 * выпадающая панель у трея.
 */
enum class GlassTone { SCREEN, WINDOW, PANEL }

private val WindowGlass = Color(0xA3241F2A)
private val Plum = Color(0x99805678)
private val Clay = Color(0x57D6805C)
private val Dusk = Color(0x80545E8C)

private fun DrawScope.spot(center: Offset, radius: Float, color: Color) =
    drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center, radius), radius, center)

/**
 * Обои: земля и пятна света.
 *
 * У экрана телефона пятен четыре и они ярче — между ними нет тёмного стекла окна (макет «Объект
 * · телефон»): слива сверху слева, глина справа, сумерки внизу и вторая слива в нижнем углу.
 */
fun DrawScope.drawWallpaper(tone: GlassTone) {
    drawRect(PointPalette.canvas)
    val w = size.width
    val h = size.height
    if (tone == GlassTone.SCREEN) {
        val side = minOf(w, h * 0.45f)
        spot(Offset(w * 0.24f, h * 0.09f), side * 1.02f, Plum.copy(alpha = 0.78f))
        spot(Offset(w * 0.92f, h * 0.27f), side * 0.84f, Clay.copy(alpha = 0.36f))
        spot(Offset(w * 0.46f, h * 0.84f), side * 1.16f, Dusk.copy(alpha = 0.72f))
        spot(Offset(w * 0.9f, h * 0.91f), side * 0.62f, Plum.copy(alpha = 0.52f))
        return
    }
    spot(Offset(w * 0.02f, h * 0.12f), maxOf(w, h) * 0.5f, Plum)
    spot(Offset(w * 0.95f, h * 0.1f), maxOf(w, h) * 0.42f, Clay)
    spot(Offset(w * 0.5f, h * 1.05f), maxOf(w, h) * 0.52f, Dusk)
    when (tone) {
        GlassTone.WINDOW -> drawRect(WindowGlass)
        GlassTone.PANEL -> drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.05f))))
        GlassTone.SCREEN -> Unit
    }
}

/** Кромка стекла: тонкая, у верхнего края светлее — свет падает сверху. */
fun glassEdge(top: Float, rest: Float): Brush = Brush.verticalGradient(
    0f to Color.White.copy(alpha = top),
    0.05f to Color.White.copy(alpha = rest),
    1f to Color.White.copy(alpha = rest),
)

/**
 * Светлая главная строка: первое действие, «Новый объект», кнопка главного шага. Тёмный текст на
 * светлом — главное стоит первой строкой списка, а не баннером над ним (#879).
 */
fun Modifier.lightSurface(shape: Shape = RoundedCornerShape(14.dp)): Modifier = this
    .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
    .clip(shape)
    .background(Brush.verticalGradient(listOf(PointPalette.lightTop, PointPalette.lightBottom)))
    .border(1.dp, Brush.verticalGradient(0f to Color.White.copy(alpha = 0.95f), 0.1f to Color.Transparent), shape)

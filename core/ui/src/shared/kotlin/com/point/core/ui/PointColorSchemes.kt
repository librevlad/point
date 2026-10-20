package com.point.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Схема цветов Point — одна на телефон и ПК (#853).
 *
 * После #851 цвета оба устройства брали из общей палитры, но схему собирали по-своему:
 * телефон заполнял девятнадцать ролей, ПК — десять, остальные оставались умолчаниями
 * Material, то есть чужой палитрой. И роли назначались разные: `secondary` на телефоне
 * приглушённый серый, на ПК был cyan.
 *
 * Схема — это то, чем красятся стандартные компоненты. Пока их на ПК почти нет,
 * расхождение не видно; появится первая кнопка или переключатель — и он окажется другого
 * цвета, чем такой же на телефоне.
 *
 * Роли Material непрозрачны: меню, поле ввода и переключатель рисуют ими заливку, и
 * полупрозрачное стекло палитры сделало бы меню сквозным. Поэтому поверхности здесь — то же
 * стекло, сведённое на землю в один цвет.
 */

private val Ink = Color(0xFF0F1626)
private val Orange = Color(0xFFF5610F)
private val Purple = Color(0xFF7C4DFF)

/** Светлая схема: пока её включает только предпросмотр экранов. */
val PointLightColors: ColorScheme = lightColorScheme(
    primary = Orange,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE9DB),
    onPrimaryContainer = Color(0xFFB15A1E),
    secondary = Ink,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E7EC),
    onSecondaryContainer = Ink,
    tertiary = Purple,
    background = Color(0xFFF4F5F7),
    onBackground = Ink,
    surface = Color(0xFFFFFFFF),
    onSurface = Ink,
    surfaceVariant = Color(0xFFEEF0F3),
    onSurfaceVariant = Color(0xFF8A93A0),
    outline = Color(0xFFCFD3DA),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/** Подпись на земле, сведённая в один цвет: `muted` палитры поверх `canvas`. */
private val MutedSolid = Color(0xFF9C959C)

/** Стекло строки и панели, сведённое на землю: от самого тёмного к самому светлому. */
private val GlassLowest = Color(0xFF241F2A)
private val GlassLow = Color(0xFF2F2936)
private val Glass = Color(0xFF342D3B)
private val GlassHigh = Color(0xFF3A3242)
private val GlassHighest = Color(0xFF41394A)

/** Тёмная — то, что человек видит и на телефоне, и на ПК. */
val PointDarkColors: ColorScheme = darkColorScheme(
    primary = PointPalette.violet,
    onPrimary = PointPalette.ink,
    primaryContainer = Color(0xFF453B58),
    onPrimaryContainer = Color(0xFFE6DDF7),
    inversePrimary = Color(0xFF6B5A9C),
    secondary = MutedSolid,
    onSecondary = PointPalette.ink,
    secondaryContainer = GlassHigh,
    onSecondaryContainer = PointPalette.text,
    tertiary = PointPalette.cyan,
    onTertiary = PointPalette.ink,
    background = PointPalette.canvas,
    onBackground = PointPalette.text,
    surface = Glass,
    onSurface = PointPalette.text,
    surfaceVariant = GlassHigh,
    onSurfaceVariant = MutedSolid,
    surfaceTint = PointPalette.violet,
    inverseSurface = PointPalette.text,
    inverseOnSurface = PointPalette.ink,
    error = PointPalette.warm,
    onError = PointPalette.ink,
    errorContainer = Color(0xFF5A3428),
    onErrorContainer = Color(0xFFF6D3C4),
    outline = Color(0xFF5B5262),
    outlineVariant = Color(0xFF453D4D),
    scrim = Color(0xFF000000),
    surfaceBright = GlassHighest,
    surfaceDim = GlassLowest,
    surfaceContainerLowest = GlassLowest,
    surfaceContainerLow = GlassLow,
    surfaceContainer = Glass,
    surfaceContainerHigh = GlassHigh,
    surfaceContainerHighest = GlassHighest,
)

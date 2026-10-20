package com.point.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind

/**
 * Обои под экраном телефона: тёплая земля и мягкие пятна света (макет 19.10.2026) — всё стекло
 * лежит на них.
 *
 * Прежний фон двигался шейдером и жил только на Android 13+, а ниже был ровной заливкой; обои
 * макета неподвижны и одинаковы на любом телефоне. Рисуются без чтения и без сети — первому
 * экрану (≤ 300 мс, без I/O) они ничего не стоят.
 */
fun Modifier.wallpaper(): Modifier = drawBehind { drawWallpaper(GlassTone.SCREEN) }

/**
 * Корень экрана Point: обои и светлый текст по умолчанию. Им начинается каждая дверь, которой
 * система зовёт Point, — «Поделиться», значок приложения, источники, вход.
 */
@Composable
fun PointScreen(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    CompositionLocalProvider(LocalContentColor provides PointPalette.text) {
        Box(modifier.fillMaxSize().wallpaper(), content = content)
    }
}

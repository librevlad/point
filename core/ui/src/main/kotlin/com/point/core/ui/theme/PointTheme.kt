package com.point.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.point.core.ui.PointDarkColors
import com.point.core.ui.PointLightColors

@Composable
fun PointTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) PointDarkColors else PointLightColors,
        typography = PointTypography,
        content = content,
    )
}

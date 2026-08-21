package com.point.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.tooling.preview.Preview
import com.point.core.ui.theme.PointTheme
import com.point.core.flow.Box as PageBox

private fun previewPage(): ImageBitmap {
    val width = 600
    val height = 840
    val bitmap = ImageBitmap(width, height)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    paint.color = Color(0xFFF2F3F5)
    canvas.drawRect(Rect(0f, 0f, width.toFloat(), height.toFloat()), paint)
    paint.color = Color(0xFF9AA1AC)
    var y = 70f
    repeat(17) { line ->
        val lineWidth = 470f - (line % 4) * 80f
        canvas.drawRect(Rect(60f, y, 60f + lineWidth, y + 15f), paint)
        y += 44f
    }
    return bitmap
}

@Preview(name = "Поиск · нашлось (#461)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewFindFound() = PointTheme(darkTheme = true) {

    FindScreen(
        image = remember { previewPage() },
        highlights = listOf(PageBox(60f, 158f, 300f, 173f), PageBox(60f, 466f, 260f, 481f)),
        status = "Найдено: 2",
        onQuery = {},
        onClose = {},
    )
}

@Preview(name = "Поиск · ещё не искали (#461)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewFindUntouched() = PointTheme(darkTheme = true) {

    FindScreen(
        image = remember { previewPage() },
        highlights = emptyList(),
        status = null,
        onQuery = {},
        onClose = {},
    )
}

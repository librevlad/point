package com.point.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.point.core.model.Bubble
import com.point.core.model.ExecutorId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.ui.theme.PointTheme

/*
 * Preview harness — this is how you iterate the first screen WITHOUT building an
 * APK or touching a device. Open any function below in Android Studio's Split /
 * Design view; edits re-render live. Each mirrors what the registry produces for
 * that object kind.
 */

private fun sampleObject(kind: ObjectKind, mime: String, name: String) = PointObject(
    id = "preview",
    mime = mime,
    uri = ScratchRef("/preview/$name"),
    state = ObjectState(kind),
    metadata = mapOf("name" to name),
)

private fun universalBubbles(kind: ObjectKind) = listOf(
    Bubble("share", "Поделиться", ExecutorId("share"), ObjectState(kind)),
    Bubble("save", "Сохранить", ExecutorId("save"), ObjectState(kind)),
    Bubble("ai", "AI", ExecutorId("ai"), ObjectState(ObjectKind.TEXT)),
)

private fun sampleBubbles(kind: ObjectKind): List<Bubble> = universalBubbles(kind) + when (kind) {
    ObjectKind.IMAGE -> listOf(
        Bubble("compress", "Сжать", ExecutorId("image"), ObjectState(ObjectKind.IMAGE)),
        Bubble("pdf", "В PDF", ExecutorId("pdf"), ObjectState(ObjectKind.PDF)),
    )
    ObjectKind.PDF -> listOf(
        Bubble("pdf", "Извлечь текст", ExecutorId("pdf"), ObjectState(ObjectKind.TEXT)),
        Bubble("translate", "Перевести", ExecutorId("translate"), ObjectState(ObjectKind.TEXT)),
    )
    ObjectKind.TEXT -> listOf(
        Bubble("pdf", "В PDF", ExecutorId("pdf"), ObjectState(ObjectKind.PDF)),
        Bubble("translate", "Перевести", ExecutorId("translate"), ObjectState(ObjectKind.TEXT)),
    )
    ObjectKind.ZIP -> listOf(
        Bubble("unzip", "Распаковать", ExecutorId("zip"), ObjectState(ObjectKind.UNKNOWN)),
    )
    else -> emptyList()
}

@Preview(name = "Image", showBackground = true)
@Composable
private fun PreviewImage() = PointTheme {
    val obj = sampleObject(ObjectKind.IMAGE, "image/jpeg", "photo.jpg")
    FirstScreen(obj = obj, bubbles = sampleBubbles(ObjectKind.IMAGE), onBubble = {})
}

@Preview(name = "PDF", showBackground = true)
@Composable
private fun PreviewPdf() = PointTheme {
    val obj = sampleObject(ObjectKind.PDF, "application/pdf", "report.pdf")
    FirstScreen(obj = obj, bubbles = sampleBubbles(ObjectKind.PDF), onBubble = {})
}

@Preview(name = "Zip", showBackground = true)
@Composable
private fun PreviewZip() = PointTheme {
    val obj = sampleObject(ObjectKind.ZIP, "application/zip", "album.zip")
    FirstScreen(obj = obj, bubbles = sampleBubbles(ObjectKind.ZIP), onBubble = {})
}

@Preview(name = "Text + Failure message", showBackground = true)
@Composable
private fun PreviewTextWithMessage() = PointTheme {
    val obj = sampleObject(ObjectKind.TEXT, "text/plain", "notes.txt")
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.TEXT),
        onBubble = {},
        message = "Действие подключим в следующем срезе",
    )
}

@Preview(name = "Image · dark", showBackground = true)
@Composable
private fun PreviewImageDark() = PointTheme(darkTheme = true) {
    val obj = sampleObject(ObjectKind.IMAGE, "image/png", "screenshot.png")
    FirstScreen(obj = obj, bubbles = sampleBubbles(ObjectKind.IMAGE), onBubble = {})
}

@Preview(name = "AI · needs input", showBackground = true)
@Composable
private fun PreviewNeedsInput() = PointTheme {
    val obj = sampleObject(ObjectKind.IMAGE, "image/jpeg", "photo.jpg")
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.IMAGE),
        onBubble = {},
        inputPrompt = "Что сделать с объектом? (пусто = авто-анализ)",
    )
}

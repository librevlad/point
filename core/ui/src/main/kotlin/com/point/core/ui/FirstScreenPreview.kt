package com.point.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
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
    Bubble("open", "Открыть", CapabilityId("open"), ObjectState(kind)),
    Bubble("share", "Поделиться", CapabilityId("share"), ObjectState(kind)),
    Bubble("save", "Сохранить", CapabilityId("save"), ObjectState(kind)),
    Bubble("ai", "AI", CapabilityId("ai"), ObjectState(ObjectKind.TEXT)),
)

private fun sampleBubbles(kind: ObjectKind): List<Bubble> =
    if (kind == ObjectKind.COLLECTION)
        listOf(
            Bubble("save-all", "Сохранить всё", CapabilityId("save-all"), ObjectState(ObjectKind.COLLECTION)),
            Bubble("share", "Поделиться всем", CapabilityId("share-all"), ObjectState(ObjectKind.COLLECTION)),
            Bubble("pdf", "Объединить в PDF", CapabilityId("merge-pdf"), ObjectState(ObjectKind.PDF)),
        )
    else universalBubbles(kind) + when (kind) {
    ObjectKind.IMAGE -> listOf(
        Bubble("compress", "Сжать", CapabilityId("image"), ObjectState(ObjectKind.IMAGE)),
        Bubble("pdf", "В PDF", CapabilityId("pdf"), ObjectState(ObjectKind.PDF)),
        Bubble("excel", "В Excel", CapabilityId("excel"), ObjectState(ObjectKind.OFFICE)),
    )
    ObjectKind.PDF -> listOf(
        Bubble("pdf", "Извлечь текст", CapabilityId("pdf"), ObjectState(ObjectKind.TEXT)),
        Bubble("pages", "Страницы", CapabilityId("pdf-pages"), ObjectState(ObjectKind.COLLECTION)),
        Bubble("translate", "Перевести", CapabilityId("translate"), ObjectState(ObjectKind.TEXT)),
        Bubble("excel", "В Excel", CapabilityId("excel"), ObjectState(ObjectKind.OFFICE)),
    )
    ObjectKind.TEXT -> listOf(
        Bubble("pdf", "В PDF", CapabilityId("pdf"), ObjectState(ObjectKind.PDF)),
        Bubble("translate", "Перевести", CapabilityId("translate"), ObjectState(ObjectKind.TEXT)),
        Bubble("excel", "В Excel", CapabilityId("excel"), ObjectState(ObjectKind.OFFICE)),
    )
    ObjectKind.ZIP -> listOf(
        Bubble("unzip", "Распаковать", CapabilityId("zip"), ObjectState(ObjectKind.UNKNOWN)),
    )
    else -> emptyList()
}

/** Intents mirror what the registry derives for that kind (the intent-first surface). */
private fun sampleIntents(kind: ObjectKind): List<Intent> = when (kind) {
    ObjectKind.COLLECTION -> listOf(Intent.PREPARE, Intent.SEND)
    ObjectKind.ZIP -> listOf(Intent.PREPARE, Intent.OPEN, Intent.SEND)
    ObjectKind.UNKNOWN -> listOf(Intent.UNDERSTAND, Intent.OPEN, Intent.SEND)
    else -> listOf(Intent.UNDERSTAND, Intent.PREPARE, Intent.OPEN, Intent.SEND)
}

@Preview(name = "Image", showBackground = true)
@Composable
private fun PreviewImage() = PointTheme {
    val obj = sampleObject(ObjectKind.IMAGE, "image/jpeg", "photo.jpg")
    FirstScreen(obj = obj, bubbles = sampleBubbles(ObjectKind.IMAGE), onBubble = {}, intents = sampleIntents(ObjectKind.IMAGE))
}

@Preview(name = "PDF", showBackground = true)
@Composable
private fun PreviewPdf() = PointTheme {
    val obj = sampleObject(ObjectKind.PDF, "application/pdf", "report.pdf")
    FirstScreen(obj = obj, bubbles = sampleBubbles(ObjectKind.PDF), onBubble = {}, intents = sampleIntents(ObjectKind.PDF))
}

@Preview(name = "Zip", showBackground = true)
@Composable
private fun PreviewZip() = PointTheme {
    val obj = sampleObject(ObjectKind.ZIP, "application/zip", "album.zip")
    FirstScreen(obj = obj, bubbles = sampleBubbles(ObjectKind.ZIP), onBubble = {}, intents = sampleIntents(ObjectKind.ZIP))
}

@Preview(name = "Collection · unpacked archive", showBackground = true)
@Composable
private fun PreviewCollection() = PointTheme {
    val obj = sampleObject(ObjectKind.COLLECTION, "inode/directory", "album (распаковано)")
    val items = listOf(
        sampleObject(ObjectKind.IMAGE, "image/jpeg", "photo-1.jpg"),
        sampleObject(ObjectKind.IMAGE, "image/png", "photo-2.png"),
        sampleObject(ObjectKind.PDF, "application/pdf", "readme.pdf"),
        sampleObject(ObjectKind.TEXT, "text/plain", "notes.txt"),
    )
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.COLLECTION),
        onBubble = {},
        intents = sampleIntents(ObjectKind.COLLECTION),
        items = items,
        onItem = {},
    )
}

@Preview(name = "Unknown file · Открыть", showBackground = true)
@Composable
private fun PreviewUnknownOpen() = PointTheme {
    val obj = sampleObject(ObjectKind.UNKNOWN, "application/octet-stream", "data.bin")
    FirstScreen(obj = obj, bubbles = sampleBubbles(ObjectKind.UNKNOWN), onBubble = {}, intents = sampleIntents(ObjectKind.UNKNOWN))
}

@Preview(name = "Text + Failure message", showBackground = true)
@Composable
private fun PreviewTextWithMessage() = PointTheme {
    val obj = sampleObject(ObjectKind.TEXT, "text/plain", "notes.txt")
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.TEXT),
        onBubble = {},
        intents = sampleIntents(ObjectKind.TEXT),
        message = "Действие подключим в следующем срезе",
    )
}

@Preview(name = "Text · встроенный просмотр", showBackground = true)
@Composable
private fun PreviewTextRead() = PointTheme {
    val obj = sampleObject(ObjectKind.TEXT, "text/plain", "notes.txt")
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.TEXT),
        onBubble = {},
        intents = sampleIntents(ObjectKind.TEXT),
        textPreview = "Заголовок заметки\n\nАбзац текста, который прокручивается, " +
            "выделяется и копируется прямо в Point — не выходя во внешнее приложение.\n\n" +
            "• пункт один\n• пункт два\n• пункт три\n\nЕщё строка для объёма.",
    )
}

@Preview(name = "Text · markdown (AI-результат)", showBackground = true)
@Composable
private fun PreviewMarkdown() = PointTheme(darkTheme = true) {
    val obj = sampleObject(ObjectKind.TEXT, "text/markdown", "ответ.md")
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.TEXT),
        onBubble = {},
        intents = sampleIntents(ObjectKind.TEXT),
        // Renders as heading + bold + bullets, not raw ###/**/*.
        textPreview = "### Анализ запроса\n" +
            "* **Тип запроса:** Составление технической карты (ТТК).\n" +
            "* **Объект:** Блюдо «Фасоль с пасеровкой».\n" +
            "* **Проблема:** есть только ингредиенты и веса, нет технологии приготовления.",
    )
}

@Preview(name = "Image · dark", showBackground = true)
@Composable
private fun PreviewImageDark() = PointTheme(darkTheme = true) {
    val obj = sampleObject(ObjectKind.IMAGE, "image/png", "screenshot.png")
    FirstScreen(obj = obj, bubbles = sampleBubbles(ObjectKind.IMAGE), onBubble = {}, intents = sampleIntents(ObjectKind.IMAGE))
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

@Preview(name = "Intent · Подготовить (раскрыто)", showBackground = true)
@Composable
private fun PreviewIntentDrill() = PointTheme {
    val obj = sampleObject(ObjectKind.IMAGE, "image/jpeg", "photo.jpg")
    // The user tapped «Подготовить»; that intent's capabilities are revealed.
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.IMAGE).filter { it.icon in setOf("compress", "pdf", "excel") },
        onBubble = {},
        intents = sampleIntents(ObjectKind.IMAGE),
        selectedIntent = Intent.PREPARE,
    )
}

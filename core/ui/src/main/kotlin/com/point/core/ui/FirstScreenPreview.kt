package com.point.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.point.core.model.Bubble
import com.point.core.model.BubbleTier
import com.point.core.model.CapabilityId
import com.point.core.model.LatentBubble
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.ui.theme.PointTheme

/*
 * Preview harness — this is how you iterate the first screen WITHOUT building an
 * APK or touching a device. Open any function below in Android Studio's Split /
 * Design view; edits re-render live. Each mirrors what the registry produces for
 * that object kind — including the #114 shape: понял-card, likely three, folded rest.
 */

private fun sampleObject(
    kind: ObjectKind,
    mime: String,
    name: String,
    features: Set<com.point.core.model.Feature> = emptySet(),
    metadata: Map<String, String> = emptyMap(),
) = PointObject(
    id = "preview",
    mime = mime,
    uri = ScratchRef("/preview/$name"),
    state = ObjectState(kind, features),
    metadata = metadata + mapOf("name" to name),
)

private fun bubble(icon: String, title: String, id: String, next: ObjectKind, tier: BubbleTier) =
    Bubble(icon, title, CapabilityId(id), ObjectState(next), tier)

private fun universalBubbles(kind: ObjectKind) = listOf(
    bubble("open", "Открыть", "open", kind, BubbleTier.INSTANT),
    bubble("share", "Поделиться", "share", kind, BubbleTier.INSTANT),
    bubble("save", "Сохранить", "save", kind, BubbleTier.INSTANT),
    bubble("ai", "AI", "ai", ObjectKind.TEXT, BubbleTier.AI),
)

private fun sampleBubbles(kind: ObjectKind): List<Bubble> =
    if (kind == ObjectKind.COLLECTION)
        listOf(
            bubble("save-all", "Сохранить всё", "save-all", ObjectKind.COLLECTION, BubbleTier.INSTANT),
            bubble("share", "Поделиться всем", "share-all", ObjectKind.COLLECTION, BubbleTier.INSTANT),
            bubble("pdf", "Объединить в PDF", "merge-pdf", ObjectKind.PDF, BubbleTier.SMART),
        )
    else when (kind) {
        ObjectKind.IMAGE -> listOf(
            bubble("ocr", "Распознать текст", "ocr", ObjectKind.TEXT, BubbleTier.SMART),
            bubble("compress", "Сжать", "image", ObjectKind.IMAGE, BubbleTier.SMART),
            bubble("scan", "Скан", "scan", ObjectKind.IMAGE, BubbleTier.SMART),
            bubble("pdf", "В PDF", "pdf", ObjectKind.PDF, BubbleTier.SMART),
            bubble("excel", "В Excel", "excel", ObjectKind.OFFICE, BubbleTier.AI),
            bubble("ocr-cloud", "Распознать в облаке", "ocr-cloud", ObjectKind.TEXT, BubbleTier.AI),
        ) + universalBubbles(kind)
        ObjectKind.PDF -> listOf(
            bubble("pdf", "Извлечь текст", "pdf", ObjectKind.TEXT, BubbleTier.SMART),
            bubble("pages", "Страницы", "pdf-pages", ObjectKind.COLLECTION, BubbleTier.SMART),
            bubble("translate", "Перевести", "translate", ObjectKind.TEXT, BubbleTier.AI),
            bubble("excel", "В Excel", "excel", ObjectKind.OFFICE, BubbleTier.AI),
        ) + universalBubbles(kind)
        ObjectKind.TEXT -> listOf(
            bubble("call", "Позвонить", "call", ObjectKind.TEXT, BubbleTier.INSTANT),
            bubble("event", "Создать событие", "event", ObjectKind.TEXT, BubbleTier.INSTANT),
            bubble("list", "Собрать данные", "extract-all", ObjectKind.TEXT, BubbleTier.SMART),
            bubble("pdf", "В PDF", "pdf", ObjectKind.PDF, BubbleTier.SMART),
            bubble("translate", "Перевести", "translate", ObjectKind.TEXT, BubbleTier.AI),
        ) + universalBubbles(kind)
        ObjectKind.ZIP -> listOf(
            bubble("unzip", "Распаковать", "zip", ObjectKind.UNKNOWN, BubbleTier.SMART),
        ) + universalBubbles(kind)
        else -> universalBubbles(kind)
    }

@Preview(name = "Скриншот · Point понял (#114)", showBackground = true)
@Composable
private fun PreviewUnderstoodScreenshot() = PointTheme {
    // The #64 → #114 showcase: an OCR'd screenshot whose entities lit up on the image
    // itself — the understanding card carries the values, actions follow from them.
    val obj = sampleObject(
        ObjectKind.IMAGE, "image/png", "screenshot.png",
        features = setOf(
            com.point.core.model.Feature.HAS_PHONE,
            com.point.core.model.Feature.HAS_DATE,
            com.point.core.model.Feature.HAS_URL,
        ),
        metadata = mapOf(
            "entity.phone" to "+380 67 123 45 67",
            "entity.date" to "завтра в 18:00",
            "entity.url" to "https://point.app/demo",
        ),
    )
    FirstScreen(obj = obj, bubbles = sampleBubbles(ObjectKind.IMAGE), onBubble = {})
}

@Preview(name = "Скриншот · Point думает", showBackground = true)
@Composable
private fun PreviewThinking() = PointTheme {
    // Mid-enrichment: one fact already landed, OCR still running — the card shows both.
    val obj = sampleObject(
        ObjectKind.IMAGE, "image/png", "screenshot.png",
        features = setOf(com.point.core.model.Feature.HAS_QR),
        metadata = mapOf("entity.qr" to "https://wifi.setup/qr"),
    )
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.IMAGE),
        onBubble = {},
        enriching = listOf("Распознаю текст…"),
    )
}

@Preview(name = "Скриншот · Момент чтения (#114)", showBackground = true)
@Composable
private fun PreviewReadingBeat() = PointTheme {
    // Reading-beat: обогащение ещё идёт (свип + ignite живы), но факты уже упали —
    // аура высоко по своей рампе. Момент «он понял» в полёте.
    val obj = sampleObject(
        ObjectKind.IMAGE, "image/png", "чек.jpg",
        features = setOf(
            com.point.core.model.Feature.IS_PURCHASE,
            com.point.core.model.Feature.HAS_PHONE,
            com.point.core.model.Feature.HAS_ADDRESS,
        ),
        metadata = mapOf(
            "entity.phone" to "+380 67 123 45 67",
            "entity.address" to "вул. Хрещатик, 1",
        ),
    )
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.IMAGE),
        onBubble = {},
        enriching = listOf("Распознаю текст…"),
    )
}

@Preview(name = "Image · без фактов", showBackground = true)
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
        items = items,
        onItem = {},
    )
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

@Preview(name = "Text · встроенный просмотр", showBackground = true)
@Composable
private fun PreviewTextRead() = PointTheme {
    val obj = sampleObject(
        ObjectKind.TEXT, "text/plain", "notes.txt",
        features = setOf(com.point.core.model.Feature.HAS_PHONE, com.point.core.model.Feature.HAS_DATE),
        metadata = mapOf("entity.phone" to "+380 67 123 45 67", "entity.date" to "завтра 18:00"),
    )
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.TEXT),
        onBubble = {},
        textPreview = "Встретимся завтра в 18:00.\nЗвони +380 67 123 45 67 если что.",
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
        // Renders as heading + bold + bullets, not raw ###/**/*.
        textPreview = "### Анализ запроса\n" +
            "* **Тип запроса:** Составление технической карты (ТТК).\n" +
            "* **Объект:** Блюдо «Фасоль с пасеровкой».\n" +
            "* **Проблема:** есть только ингредиенты и веса, нет технологии приготовления.",
    )
}

@Preview(name = "Image · dark + negotiation", showBackground = true)
@Composable
private fun PreviewImageDark() = PointTheme(darkTheme = true) {
    val obj = sampleObject(ObjectKind.IMAGE, "image/png", "screenshot.png")
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.IMAGE),
        onBubble = {},
        // Negotiation (#97): translate/open-url are one OCR away.
        latent = listOf(
            LatentBubble("translate", "Перевести", "сначала распознайте текст"),
            LatentBubble("link", "Открыть ссылку", "сначала распознайте текст"),
        ),
    )
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
        inputSuggestions = listOf("Что на изображении?", "Извлеки весь текст", "Переведи текст с картинки"),
    )
}

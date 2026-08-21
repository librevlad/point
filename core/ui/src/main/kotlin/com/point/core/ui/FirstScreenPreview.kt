package com.point.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.point.core.flow.KIND_ADDRESS
import com.point.core.flow.KIND_DATE
import com.point.core.flow.KIND_IDENTIFIER
import com.point.core.flow.KIND_ORGANIZATION
import com.point.core.model.ActionYield
import com.point.core.model.Bubble
import com.point.core.model.BubbleTier
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.LatentBubble
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.ui.theme.PointTheme

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

private fun previewIntent(id: String, next: ObjectKind): Intent = when {
    id.startsWith("open") -> Intent.OPEN
    id in setOf("share", "share-all", "save", "save-all", "call") -> Intent.SEND
    next == ObjectKind.TEXT -> Intent.UNDERSTAND
    else -> Intent.PREPARE
}

private fun previewYield(id: String, next: ObjectKind, intent: Intent): ActionYield = when (id) {
    "ai" -> ActionYield.Unknown
    "understand" -> ActionYield.Same()
    "excel" -> ActionYield.New(ObjectKind.OFFICE, "таблицу")
    "word", "word-plus" -> ActionYield.New(ObjectKind.OFFICE, "документ Word")
    else -> if (intent == Intent.OPEN || intent == Intent.SEND) ActionYield.None else ActionYield.New(next)
}

private fun bubble(
    icon: String,
    title: String,
    id: String,
    next: ObjectKind,
    tier: BubbleTier,
    intent: Intent = previewIntent(id, next),
) = Bubble(
    icon, title, CapabilityId(id), ObjectState(next), tier,
    intent = intent,
    yields = previewYield(id, next, intent),
)

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
        ObjectKind.OFFICE -> listOf(
            bubble("office", "Извлечь текст", "office", ObjectKind.TEXT, BubbleTier.SMART),
            bubble("pdf", "В PDF", "pdf", ObjectKind.PDF, BubbleTier.SMART),
        ) + universalBubbles(kind)
        else -> universalBubbles(kind)
    }

@Preview(name = "Скриншот · Point понял (#114)", showBackground = true)
@Composable
private fun PreviewUnderstoodScreenshot() = PointTheme {

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

@Preview(name = "Посылка · Point нашёл вещи (#222)", showBackground = true)
@Composable
private fun PreviewFoundObjects() = PointTheme {

    val obj = sampleObject(
        ObjectKind.IMAGE, "image/png", "Screenshot_Nova Post.jpg",
        features = setOf(
            com.point.core.model.Feature.HAS_ADDRESS,
            com.point.core.model.Feature.HAS_DATE,
        ),

        metadata = mapOf(
            "entity.address" to "Відділення №9, вул. Хрещатик, 1",
            "entity.address.src" to com.point.core.model.Provenance.OCR.wire,
            "entity.date" to "29.07 до 18:00",
            "entity.date.src" to com.point.core.model.Provenance.OCR.wire,

            com.point.core.flow.META_ENTITY_TRACK to "20 4514 9154 9395",
            com.point.core.flow.META_ENTITY_TRACK + com.point.core.flow.META_SOURCE_SUFFIX to
                com.point.core.model.Provenance.OCR.wire,

            com.point.core.flow.META_ENTITY_TRACK + com.point.core.flow.META_EVIDENCE_SUFFIX to "semantic",
            "graph.role.carrier" to "Нова Пошта",
            "graph.role.carrier.src" to com.point.core.model.Provenance.MODEL.wire,

            com.point.core.flow.META_SEMANTIC_TYPE to com.point.core.flow.TYPE_PARCEL,
        ),
    )
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.IMAGE),
        onBubble = {},
        found = listOf(

            foundObject(
                "o:id", KIND_IDENTIFIER, "20 4514 9154 9395",
                key = com.point.core.flow.META_ENTITY_TRACK,
                evidence = "semantic",
            ),
            foundObject("o:address", KIND_ADDRESS, "Відділення №9, вул. Хрещатик, 1", key = "entity.address"),
            foundObject("o:date", KIND_DATE, "29.07 до 18:00", key = "entity.date"),

            foundObject(
                "o:org", KIND_ORGANIZATION, "Нова Пошта",
                key = "graph.role.carrier",
                provenance = com.point.core.model.Provenance.MODEL,
            ),
        ),
        relations = listOf(
            com.point.core.model.Relation(
                "o:org", com.point.core.model.RelationType.CARRIER, "preview",
            ),
        ),
    )
}

@Preview(name = "Посылка · не хватает только трека (#260)", showBackground = true)
@Composable
private fun PreviewReadinessMissing() = PointTheme {

    FirstScreen(
        obj = sampleObject(
            ObjectKind.IMAGE, "image/png", "photo_label.jpg",
            metadata = mapOf(
                "graph.role.carrier" to "Нова Пошта",
                com.point.core.flow.META_SEMANTIC_TYPE to com.point.core.flow.TYPE_PARCEL,
            ),
        ),
        bubbles = sampleBubbles(ObjectKind.IMAGE),
        onBubble = {},
    )
}

@Preview(name = "Карточка готовности · строка = действие (#464)", showBackground = true)
@Composable
private fun PreviewReadinessActionable() = PointTheme {

    FirstScreen(
        obj = sampleObject(
            ObjectKind.IMAGE, "image/png", "parcel_with_phone.jpg",
            features = setOf(com.point.core.model.Feature.HAS_PHONE),
            metadata = mapOf(
                com.point.core.flow.META_ENTITY_TRACK to "20 4514 9154 9395",
                com.point.core.flow.META_ENTITY_TRACK + com.point.core.flow.META_EVIDENCE_SUFFIX to
                    "semantic,arithmetic",
                "graph.role.carrier" to "Нова Пошта",
                "entity.phone" to "+380 50 432 77 07",
                com.point.core.flow.META_SEMANTIC_TYPE to com.point.core.flow.TYPE_PARCEL,
            ),
        ),

        bubbles = listOf(
            bubble(
                "contact", "Сохранить контакт", "save-contact",
                ObjectKind.IMAGE, BubbleTier.INSTANT, Intent.OPEN,
            ),
        ) + sampleBubbles(ObjectKind.IMAGE),
        onBubble = {},
    )
}

@Preview(name = "Счётчик · ведущие нули барабана (#262)", showBackground = true)
@Composable
private fun PreviewMeterDrumZeros() = PointTheme {

    FirstScreen(
        obj = sampleObject(
            ObjectKind.IMAGE, "image/jpeg", "meter.jpg",
            metadata = mapOf(
                com.point.core.flow.META_ENTITY_METER to "00001154",
                com.point.core.flow.META_ENTITY_METER_UNIT to "м³",
                com.point.core.flow.META_ENTITY_METER + com.point.core.flow.META_SOURCE_SUFFIX to
                    com.point.core.model.Provenance.OCR.wire,
                com.point.core.flow.META_ENTITY_METER + com.point.core.flow.META_EVIDENCE_SUFFIX to
                    "semantic",
            ),
        ),
        bubbles = sampleBubbles(ObjectKind.IMAGE),
        onBubble = {},
    )
}

private fun foundObject(
    id: String,
    kind: ObjectKind,
    value: String,
    key: String,
    provenance: com.point.core.model.Provenance = com.point.core.model.Provenance.OCR,
    evidence: String? = null,
): PointObject {
    val slice = buildMap {
        put(key, value)
        put(key + com.point.core.flow.META_SOURCE_SUFFIX, provenance.wire)
        evidence?.let { put(key + com.point.core.flow.META_EVIDENCE_SUFFIX, it) }
    }
    return PointObject(
        id = id,
        mime = "text/plain",
        uri = com.point.core.model.ValueRef(value),
        state = ObjectState(kind),
        metadata = slice,
        provenance = com.point.core.flow.provenanceOf(slice, key),
    )
}

@Preview(name = "Скриншот · Point думает", showBackground = true)
@Composable
private fun PreviewThinking() = PointTheme {

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

@Preview(name = "Тихая работа говорит (#288)", showBackground = true)
@Composable
private fun PreviewQuietWorking() = PointTheme(darkTheme = true) {

    val obj = sampleObject(ObjectKind.PDF, "application/pdf", "книга.pdf")
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.PDF),
        onBubble = {},
        working = true,
        workingStage = "Разбираю PDF на страницы",
    )
}

@Preview(name = "Скриншот · Момент чтения (#114)", showBackground = true)
@Composable
private fun PreviewReadingBeat() = PointTheme {

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

/**
 * Широкий кадр с цветными углами и заметной серединой: по превью сразу видно,
 * что круг взял центр, а углы остались за кадром.
 */
private fun previewPhoto(width: Int = 480, height: Int = 300): ImageBitmap {
    val bitmap = ImageBitmap(width, height)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    val w = width.toFloat()
    val h = height.toFloat()

    paint.color = Color(0xFF2C6E63)
    canvas.drawRect(Rect(0f, 0f, w, h), paint)

    val corner = minOf(w, h) * 0.28f
    paint.color = Color(0xFFE24A3B)
    canvas.drawRect(Rect(0f, 0f, corner, corner), paint)
    canvas.drawRect(Rect(w - corner, 0f, w, corner), paint)
    canvas.drawRect(Rect(0f, h - corner, corner, h), paint)
    canvas.drawRect(Rect(w - corner, h - corner, w, h), paint)

    paint.color = Color(0xFFF6D65C)
    val band = h * 0.22f
    canvas.drawRect(Rect(w * 0.22f, h / 2f - band / 2f, w * 0.78f, h / 2f + band / 2f), paint)

    paint.color = Color(0xFF101418)
    canvas.drawCircle(Offset(w / 2f, h / 2f), minOf(w, h) * 0.11f, paint)
    return bitmap
}

@Preview(name = "Снимок в портале · круг внутри кольца (#667)", showBackground = true, backgroundColor = 0xFF07070C)
@Composable
private fun PreviewRoundPreviewInPortal() = PointTheme(darkTheme = true) {

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Portal(size = PortalPreviewSize + 68.dp)
        RoundPreview(
            image = remember { previewPhoto() },
            size = PortalPreviewSize,
            contentDescription = "фото.jpg",
        )
    }
}

@Preview(name = "Фото · шапка первого экрана (#667)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewFirstScreenWithPhoto() = PointTheme(darkTheme = true) {

    val obj = sampleObject(
        ObjectKind.IMAGE, "image/jpeg", "фото.jpg",
        features = setOf(com.point.core.model.Feature.HAS_PHONE),
        metadata = mapOf("entity.phone" to "+380 67 123 45 67"),
    )
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.IMAGE),
        onBubble = {},
        previewBitmap = remember { previewPhoto() },
    )
}

@Preview(name = "Фото · вертикальный кадр в шапке (#667)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewFirstScreenWithTallPhoto() = PointTheme(darkTheme = true) {

    val obj = sampleObject(ObjectKind.IMAGE, "image/jpeg", "скриншот.png")
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.IMAGE),
        onBubble = {},
        previewBitmap = remember { previewPhoto(width = 300, height = 640) },
    )
}

@Preview(name = "PDF", showBackground = true)
@Composable
private fun PreviewPdf() = PointTheme {
    val obj = sampleObject(ObjectKind.PDF, "application/pdf", "report.pdf")
    FirstScreen(obj = obj, bubbles = sampleBubbles(ObjectKind.PDF), onBubble = {})
}

@Preview(name = "Excel · результат «В Excel» (#295)", showBackground = true)
@Composable
private fun PreviewExcelResult() = PointTheme(darkTheme = true) {

    val obj = sampleObject(
        ObjectKind.OFFICE,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "таблица.xlsx",
    )
    FirstScreen(obj = obj, bubbles = sampleBubbles(ObjectKind.OFFICE), onBubble = {})
}

@Preview(name = "Word · знака нет, иконка типа (#295)", showBackground = true)
@Composable
private fun PreviewWordResult() = PointTheme(darkTheme = true) {

    val obj = sampleObject(
        ObjectKind.OFFICE,
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "документ.docx",
    )
    FirstScreen(obj = obj, bubbles = sampleBubbles(ObjectKind.OFFICE), onBubble = {})
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
        itemsTotal = items.size,
        onItem = {},
        onMoveItem = { _, _ -> },
    )
}

@Preview(name = "Collection · huge archive (#460)", showBackground = true)
@Composable
private fun PreviewHugeCollection() = PointTheme {
    val obj = sampleObject(ObjectKind.COLLECTION, "inode/directory", "backup (распаковано)")
    val items = (1..500).map { sampleObject(ObjectKind.IMAGE, "image/jpeg", "IMG_%04d.jpg".format(it)) }
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.COLLECTION),
        onBubble = {},
        items = items,
        itemsTotal = 1340,
        onItem = {},
    )
}

@Preview(name = "Исход · отказ (#358)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewOutcomeFailure() = PointTheme(darkTheme = true) {

    FirstScreen(
        obj = sampleObject(ObjectKind.IMAGE, "image/jpeg", "meter.jpg"),
        bubbles = sampleBubbles(ObjectKind.IMAGE),
        onBubble = {},
        message = "Не разобрал текст на этом снимке",
        messageOutcome = Outcome.FAILED,
    )
}

@Preview(name = "Исход · удача (#358)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewOutcomeDone() = PointTheme(darkTheme = true) {

    FirstScreen(
        obj = sampleObject(ObjectKind.IMAGE, "image/jpeg", "meter.jpg"),
        bubbles = sampleBubbles(ObjectKind.IMAGE),
        onBubble = {},
        message = "Сохранено: meter.jpg",
        messageOutcome = Outcome.DONE,
    )
}

@Preview(name = "Исход · человек остановил (#358)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewOutcomeStopped() = PointTheme(darkTheme = true) {

    FirstScreen(
        obj = sampleObject(ObjectKind.IMAGE, "image/jpeg", "meter.jpg"),
        bubbles = sampleBubbles(ObjectKind.IMAGE),
        onBubble = {},
        message = "Отменено",
        messageOutcome = Outcome.NONE,
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

@Preview(name = "Ссылка · код рядом со ссылкой (#388)", showBackground = true)
@Composable
private fun PreviewIssuedLink() = PointTheme {

    val obj = sampleObject(
        ObjectKind.URL, "text/uri-list", "ссылка на отчёт.pdf",
        metadata = mapOf(
            "entity.url" to "https://point.leerio.app/d/2f8c1b0a4e6d9c3f5a7b1e2d4c6f8a0b1c3d5e7f",
            "drop.expires" to "сутки",
        ),
    )
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.URL),
        onBubble = {},
        message = "Ссылка готова",
        messageOutcome = Outcome.DONE,
    )
}

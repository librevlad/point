package com.point.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.point.core.flow.KIND_ADDRESS
import com.point.core.flow.KIND_DATE
import com.point.core.flow.KIND_IDENTIFIER
import com.point.core.flow.KIND_ORGANIZATION
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

// Mirror the registry's intent derivation so previews render the object screen's grouped sections
// (Извлечь / Превратить / Отправить), not one big pile under UNDERSTAND.
private fun previewIntent(id: String, next: ObjectKind): Intent = when {
    id.startsWith("open") -> Intent.OPEN
    id in setOf("share", "share-all", "save", "save-all", "call") -> Intent.SEND
    next == ObjectKind.TEXT -> Intent.UNDERSTAND
    else -> Intent.PREPARE
}

private fun bubble(
    icon: String,
    title: String,
    id: String,
    next: ObjectKind,
    tier: BubbleTier,
    intent: Intent = previewIntent(id, next),
) = Bubble(icon, title, CapabilityId(id), ObjectState(next), tier, intent = intent)

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

@Preview(name = "Посылка · Point нашёл вещи (#222)", showBackground = true)
@Composable
private fun PreviewFoundObjects() = PointTheme {
    // The owner's real case: a Nova Poshta parcel screenshot. What used to be «Изображение» +
    // «✓ Нашёл дату 15:12» is now the three things that were actually on it. The address and
    // the deadline moved OUT of the checklist and became objects — tapping either opens a
    // screen whose «Маршрут» / «Создать событие» come from capabilities written long ago.
    val obj = sampleObject(
        ObjectKind.IMAGE, "image/png", "Screenshot_Nova Post.jpg",
        features = setOf(
            com.point.core.model.Feature.HAS_ADDRESS,
            com.point.core.model.Feature.HAS_DATE,
        ),
        // #264: документ несёт происхождение своих фактов (`.src`) — узлы наследуют его отсюда,
        // и превью показывает состояние, которое реально собирают OcrEnricher + «Понять», а не
        // документ без происхождения с узлами, у которых оно откуда-то взялось.
        metadata = mapOf(
            "entity.address" to "Відділення №9, вул. Хрещатик, 1",
            "entity.address.src" to com.point.core.model.Provenance.OCR.wire,
            "entity.date" to "29.07 до 18:00",
            "entity.date.src" to com.point.core.model.Provenance.OCR.wire,
            // #260: трек — факт, и «Отследить отправление» в карточке готовности — готово.
            com.point.core.flow.META_ENTITY_TRACK to "20 4514 9154 9395",
            com.point.core.flow.META_ENTITY_TRACK + com.point.core.flow.META_SOURCE_SUFFIX to
                com.point.core.model.Provenance.OCR.wire,
            // Улика ровно одна — форма (#264): карточка готовности честно скажет «возможно»
            // ещё до «Понять», и превью обязано это показывать.
            com.point.core.flow.META_ENTITY_TRACK + com.point.core.flow.META_EVIDENCE_SUFFIX to "semantic",
            "graph.role.carrier" to "Нова Пошта",
            "graph.role.carrier.src" to com.point.core.model.Provenance.MODEL.wire,
            // #222, шаг 5: заголовок берётся отсюда — «Посылка» вместо «Изображение».
            com.point.core.flow.META_SEMANTIC_TYPE to com.point.core.flow.TYPE_PARCEL,
        ),
    )
    FirstScreen(
        obj = obj,
        bubbles = sampleBubbles(ObjectKind.IMAGE),
        onBubble = {},
        found = listOf(
            // #264: «прочитано · возможно» — правило нашло номер дословно на странице, но улика
            // ровно одна (форма), и это видно словами, а не числом 0.8.
            foundObject(
                "o:id", KIND_IDENTIFIER, "20 4514 9154 9395",
                key = com.point.core.flow.META_ENTITY_TRACK,
                evidence = "semantic",
            ),
            foundObject("o:address", KIND_ADDRESS, "Відділення №9, вул. Хрещатик, 1", key = "entity.address"),
            foundObject("o:date", KIND_DATE, "29.07 до 18:00", key = "entity.date"),
            // #222, шаг 6 + #264: прочтение классификатора — роль видна, происхождение названо
            // своим именем («прочитано моделью»), а не спрятано за 0.7.
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
    // Полнота — по действию: перевозчик прочитан, трека нет. Строка «не хватает только:
    // трек-номер» вместо формы из девяти полей; тап раскрывает честное «в прочитанном
    // не нашлось».
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

@Preview(name = "Счётчик · ведущие нули барабана (#262)", showBackground = true)
@Composable
private fun PreviewMeterDrumZeros() = PointTheme {
    // Живой кадр устройства: водомер отдал «00001154», и это 154 м³. Значение остаётся
    // дословным — сколько разрядов значащие, знает поставщик услуги, — а строкой ниже стоит
    // то, что человек, скорее всего, передаст. Оба числа видно, и видно, какое со страницы.
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

/**
 * Найденный объект для превью — **срез метаданных своего факта**, ровно как его строят энричеры
 * (#264): значение, `<key>.src`, опционально улики `<key>.ev`.
 *
 * `provenance` здесь **выводится** из среза тем же [com.point.core.flow.provenanceOf], а не
 * задаётся отдельным параметром рядом с метаданными. Превью — главный инструмент дизайна
 * (`docs/TESTING.md`), и оно обязано показывать состояние, которое продакшн умеет произвести:
 * фикстура, где поле и `.src` разошлись, рисует владельцу подпись, которой на устройстве не
 * будет, — ровно та болезнь двух источников истины, от которой лечит срез.
 */
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

@Preview(name = "Тихая работа говорит (#288)", showBackground = true)
@Composable
private fun PreviewQuietWorking() = PointTheme(darkTheme = true) {
    // Быстрое действие идёт без экрана ожидания (M3) — и до этого среза молчало: список
    // притушен, объект «работает», а что именно происходит, человек не знал. Теперь под
    // объектом стоит та же строка, что показал бы экран ожидания.
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

@Preview(name = "Excel · результат «В Excel» (#295)", showBackground = true)
@Composable
private fun PreviewExcelResult() = PointTheme(darkTheme = true) {
    // Экран, ради которого знак и заводили: минуту ждали сеть, получили таблицу — и герой
    // говорит «это таблица», а не показывает ту же иконку документа, что docx и pptx.
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
    // Контроль к предыдущему превью: docx остаётся общим документом — знак носит только таблица.
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
        onItem = {},
    )
}

// Фон превью — ФОН дизайн-системы (#0B0D10): карточку исхода судят на поле портала, а не на белом
// листе, иначе «в языке продукта» проверить нечем.
@Preview(name = "Исход · отказ (#358)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewOutcomeFailure() = PointTheme(darkTheme = true) {
    // Живой случай, ради которого исход подняли под объект (03.08.2026): действие честно
    // отказалось, а человек не видел ничего — баннер стоял в самом низу прокрутки. Тогда это
    // было «Прочитать показание» (действие убрано в #396); слова здесь — дословный отказ
    // чтения на устройстве, который в продукте живёт. Вид — карточка портала со знаком «✕» в
    // тёплом конце фирменного градиента, а не красный блок Material.
    FirstScreen(
        obj = sampleObject(ObjectKind.IMAGE, "image/jpeg", "meter.jpg"),
        bubbles = sampleBubbles(ObjectKind.IMAGE),
        onBubble = {},
        message = "На устройстве текст не распознан",
        messageOutcome = Outcome.FAILED,
    )
}

@Preview(name = "Исход · удача (#358)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewOutcomeDone() = PointTheme(darkTheme = true) {
    // Контроль к предыдущему: тот же экран, тот же объект, удачный исход — знак «✓» светится
    // фиолетовым АКЦЕНТ1, светом самого портала. Раньше обе эти карточки были одинаково красными,
    // и «Сохранено» кричало сбоем наравне с настоящим отказом.
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
    // Третий исход, которого не было в паре «удача/отказ»: человек нажал «Отмена» на долгой
    // работе (#288). Заявлять тут нечего — ни «Готово», ни «Не получилось», — поэтому карточка
    // без знака и без света: одни слова о том, что работы больше нет.
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

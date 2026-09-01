package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.withContext

/** Стадии, которые действие рассказало за время работы, — как их слышит экран. */
internal suspend fun stagesHeard(action: suspend () -> Unit): List<String> {
    val heard = mutableListOf<String>()
    withContext(ActionProgress { heard += it }) { action() }
    return heard
}

/**
 * Текущее знание объекта в тестах (#1138): то же, чем пользуется продукт.
 *
 * Подмены здесь нет — берётся настоящий `GraphKnowledge`: сначала прочитанное с кадра из
 * графа, потом собственное содержимое объекта. Тесты остаются про поведение действия, а не
 * про то, откуда оно берёт текст.
 */
internal fun testKnowledge(
    pdf: PdfTextExtractor = PdfSaysNothing,
    store: ObjectStore = FileBackedStore,
): CurrentKnowledge = GraphKnowledge(store, pdf)

/** Ключ у человека есть: имена действий проверяются без приписки «нужен ключ». */
internal val aiKeysReady = AiReadiness { true }

/** Текстовый слой PDF, который отдаёт заранее известное. */
internal fun pdfSaying(text: String): PdfTextExtractor = object : PdfTextExtractor {
    override suspend fun extractText(obj: PointObject, atMost: Int?): String = text
}

internal object PdfSaysNothing : PdfTextExtractor {
    override suspend fun extractText(obj: PointObject, atMost: Int?): String = ""
}

/** Текстовый объект — это его файл; остального от хранилища знанию не нужно. */
internal object FileBackedStore : ObjectStore {
    override suspend fun ingest(sourceUri: String, mime: String): PointObject = error("не нужно")
    override suspend fun ingestMultiple(sources: List<String>): PointObject = error("не нужно")
    override suspend fun put(
        result: ResultObject,
        from: PointObject?,
        by: com.point.core.model.CapabilityId?,
    ): PointObject = error("не нужно")
    override suspend fun children(collection: PointObject, limit: Int) = error("не нужно")
    override suspend fun readText(obj: PointObject, limit: Int): String =
        runCatching { File(obj.uri.value).takeIf(File::isFile)?.readText()?.take(limit) }.getOrNull().orEmpty()
    override suspend fun newScratchFile(extension: String): ScratchRef =
        ScratchRef(File.createTempFile("point-test", ".$extension").apply { deleteOnExit() }.absolutePath)
    override suspend fun clear() = Unit
}

/** Режим «куда можно отправлять» в тестах: по умолчанию открыт. */
internal fun privacyAt(level: PrivacyLevel = PrivacyLevel.DEFAULT) = object : CloudPrivacySettings {
    override fun level() = level
    override suspend fun setLevel(level: PrivacyLevel) = Unit
}

package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.asFeature
import com.point.core.flow.asMetaKey
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.Realizer
import com.point.core.flow.qrMatrix
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class PcEntitiesCapability : Capability {
    override val id = CapabilityId("pc-entities")
    override val icon = "search"
    override val meta = CapabilityMeta(priority = 25)
    override fun label(state: ObjectState) = "Найти в тексте"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
}

class PcEntitiesRealizer(
    private val extractor: EntityExtractor,
) : Realizer {
    override val capabilityId = CapabilityId("pc-entities")

    // «Найти в тексте» — исследование: результат — знание на исходнике, а не новый
    // объект-отчёт (Конституция §4; аудит 2026-08-09, блок 1.1).
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult = runCatching {
        val text = File(input.uri.value).takeIf(File::isFile)?.readText()
            ?: return ActionResult.Failure("Файла объекта нет на диске", recoverable = false)
        val found = com.point.core.flow.plausibleEntities(extractor.extract(text), text)
        if (found.isEmpty()) {

            // «Не нашлось» — знание, а не сбой (Конституция §13).
            return ActionResult.Done(
                "В тексте не нашлось ни контактов, ни дат, ни сумм",
                com.point.core.model.Findings(
                    metadata = mapOf(
                        com.point.core.flow.investigationKey(capabilityId) to
                            com.point.core.flow.InvestigationState.NOT_FOUND.wire,
                    ),
                ),
            )
        }
        ActionResult.Done("Нашёл: " + summary(found), entityFindings(found))
    }.getOrElse { ActionResult.Failure("Разобрать текст не вышло — попробуйте ещё раз", recoverable = true) }

    private fun entityFindings(found: List<Entity>): com.point.core.model.Findings =
        entityKnowledge(found, capabilityId)

    private fun summary(found: List<Entity>): String = entitySummary(found)
}

/** Знание из найденных сущностей: первое значение вида, «ещё»-значения, признаки, состояние вопроса. */
fun entityKnowledge(found: List<Entity>, question: CapabilityId): com.point.core.model.Findings {
    val metadata = buildMap {
        found.groupBy { it.type }.forEach { (type, list) ->
            val key = type.asMetaKey() ?: return@forEach
            val values = list.map { it.value.trim() }.filter { it.isNotBlank() }
                .distinctBy { com.point.core.flow.normConsensus(it) }
            if (values.isEmpty()) return@forEach
            put(key, values.first())
            val more = values.drop(1)
            if (more.isNotEmpty()) {
                put(key + com.point.core.flow.META_MORE_SUFFIX, com.point.core.flow.altValue(more))
            }
        }
        put(
            com.point.core.flow.investigationKey(question),
            if (found.any { it.type.asMetaKey() != null }) {
                com.point.core.flow.InvestigationState.FOUND.wire
            } else {
                com.point.core.flow.InvestigationState.NOT_FOUND.wire
            },
        )
    }
    return com.point.core.model.Findings(
        features = found.mapNotNull { it.type.asFeature() }.toSet(),
        metadata = metadata,
    )
}

internal fun entitySummary(found: List<Entity>): String =
    found.groupBy { it.type }.entries
        .joinToString(", ") { (type, list) -> entityTitle(type).lowercase() + " — " + list.size }

private fun entityTitle(type: EntityType): String = when (type) {
    EntityType.PHONE -> "Телефоны"
    EntityType.EMAIL -> "Почты"
    EntityType.URL -> "Ссылки"
    EntityType.ADDRESS -> "Адреса"
    EntityType.DATE_TIME -> "Даты"
    EntityType.PAYMENT_CARD -> "Карты"
    EntityType.MONEY -> "Суммы"
}

private fun aiMeta(priority: Int) = CapabilityMeta(
    priority = priority,
    latency = Latency.SLOW,
    network = true,
    auth = true,
)

class PcUnderstandCapability : Capability {
    override val id = CapabilityId("pc-understand")
    override val icon = "ai"
    override val meta = aiMeta(26)
    override fun label(state: ObjectState) = "Понять"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
}

class PcTranslateCapability : Capability {
    override val id = CapabilityId("pc-translate")
    override val icon = "translate"
    override val meta = aiMeta(27)
    override fun label(state: ObjectState) = "Перевести"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
}

class PcAskCapability : Capability {
    override val id = CapabilityId("pc-ask")
    override val icon = "ai"
    override val meta = aiMeta(28)
    override fun label(state: ObjectState) = "Спросить AI"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
}

/**
 * «Понять» на компьютере — тот же протокол понимания, что на телефоне (строгий контракт
 * полей + общий парсер), результат — знание на исходнике, а не новый объект (Конституция
 * §4; аудит 2026-08-09, блок 1.1). Слоя атомов на ПК нет, поэтому судьи нет — источник MODEL.
 */
class PcUnderstandRealizer(
    private val llm: LlmClient,
) : Realizer {
    override val capabilityId = CapabilityId("pc-understand")

    override val meta = com.point.core.flow.RealizerMeta(kind = com.point.core.flow.RealizerKind.CLOUD)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult = runCatching {
        if (!llm.configured) {
            return ActionResult.Failure(
                "Ключ AI не задан — впишите его в ~/.point-pc/config строкой ai.key=…",
                recoverable = false,
            )
        }
        val answer = File(llm.run(input, pcUnderstandPrompt()).uri.value).readText()
        val parsed = com.point.core.flow.parseFieldCandidates(answer)
        val fields = parsed.fields.mapNotNull { (key, candidates) ->
            candidates.firstOrNull { com.point.core.flow.semanticFits(key, it.text) != false }
                ?.let { key to it.text }
        }.toMap()
        if (fields.isEmpty() && parsed.single.isEmpty()) {

            return ActionResult.Done(
                "Point уже прочитал всё, что здесь есть",
                com.point.core.model.Findings(
                    metadata = mapOf(
                        com.point.core.flow.investigationKey(capabilityId) to
                            com.point.core.flow.InvestigationState.NOT_FOUND.wire,
                    ),
                ),
            )
        }
        val sources = fields.keys.associate {
            it + com.point.core.flow.META_SOURCE_SUFFIX to com.point.core.model.Provenance.MODEL.wire
        }
        ActionResult.Done(
            "Стало понятнее",
            com.point.core.model.Findings(
                metadata = fields + parsed.single + sources + mapOf(
                    com.point.core.flow.investigationKey(capabilityId) to
                        com.point.core.flow.InvestigationState.FOUND.wire,
                ),
            ),
        )
    }.getOrElse { ActionResult.Failure("Сервис AI не ответил — попробуйте позже", recoverable = true) }

    private fun pcUnderstandPrompt(): String = buildString {
        append("Ниже текст документа. Найди контактные данные и номера. ")
        append(
            "Значение приводи ПОЛНОСТЬЮ, как оно есть в документе. НИЧЕГО не додумывай: " +
                "если чего-то в тексте нет — не пиши строку. Цифры не меняй. " +
                "Отвечай строками вида KEY=значение, по одной на строку. Разрешённые KEY: " +
                "PHONE, EMAIL, URL, ADDRESS, DATE, CARD, TRACK (номер отправления, дословно), " +
                "METER (показание счётчика — только цифры), GEO (координаты), PLACE (куда ехать, " +
                "если адреса нет), AMOUNT (сумма к оплате — только цифры), RECEIPT (номер " +
                "квитанции), SUBJECT (тема письма; если это не письмо — не пиши). ",
        )
        append(
            "Дополнительно: если текст целиком — встреча, строка TYPE=MEETING; покупка или " +
                "чек — TYPE=PURCHASE; рецепт — TYPE=RECIPE; вакансия — TYPE=JOB; иначе TYPE " +
                "не пиши. Добавь строку SUMMARY=<суть текста в 3-6 словах>. ",
        )
        append("Без пояснений. Если не нашлось вообще ничего — ответь ровно NONE.\n")
    }
}

class PcAiRealizer(
    override val capabilityId: CapabilityId,
    private val llm: LlmClient,
    private val prompt: String,
    private val outbox: Outbox,
    private val resultName: String,
) : Realizer {

    override val meta = com.point.core.flow.RealizerMeta(kind = com.point.core.flow.RealizerKind.CLOUD)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult = runCatching {
        if (!llm.configured) {

            return ActionResult.Failure(
                "Ключ AI не задан — впишите его в ~/.point-pc/config строкой ai.key=…",
                recoverable = false,
            )
        }

        val full = if (amendment.isNullOrBlank()) prompt else prompt + "\n" + amendment
        val result = llm.run(input, full)
        ActionResult.Success(result.copy(metadata = result.metadata + ("name" to resultName)))
    }.getOrElse { ActionResult.Failure("Сервис AI не ответил — попробуйте позже", recoverable = true) }
}

object PcPrompts {
    const val UNDERSTAND =
        "Прочитай текст и выпиши по-русски: о чём он, какие в нём есть суммы, даты, сроки, " +
            "имена и контакты, и что от человека требуется. Без вступлений и без пересказа " +
            "целиком — только суть и факты."

    const val TRANSLATE =
        "Переведи текст на русский язык. Если он уже на русском — переведи на английский. " +
            "Верни только перевод, без пояснений."

    const val ASK = "Ответь на вопрос человека по этому тексту. Коротко и по делу."
}

class PcQrRealizer(private val outbox: Outbox) : Realizer {
    override val capabilityId = com.point.core.flow.capabilities.QrCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult = runCatching {
        val text = File(input.uri.value).takeIf(File::isFile)?.readText()?.trim()
            ?: return ActionResult.Failure("Файла объекта нет на диске", recoverable = false)
        val matrix = qrMatrix(text)
            ?: return ActionResult.Failure(
                "В QR помещается короткая ссылка или строка — этот текст длиннее",
                recoverable = false,
            )
        val quiet = 4
        val side = (matrix.size + quiet * 2) * SCALE
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, side, side)
        g.color = java.awt.Color.BLACK
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix[x, y]) {
                    g.fillRect((x + quiet) * SCALE, (y + quiet) * SCALE, SCALE, SCALE)
                }
            }
        }
        g.dispose()
        val file = File.createTempFile("pc-qr-", ".png")
        ImageIO.write(image, "png", file)
        ActionResult.Success(
            com.point.core.model.ResultObject(
                type = ObjectKind.IMAGE,
                mime = "image/png",
                uri = ScratchRef(file.absolutePath),
                metadata = mapOf("name" to "QR-код"),
            ),
        )
    }.getOrElse { ActionResult.Failure("QR не собрался — попробуйте текст покороче", recoverable = true) }

    private companion object {

        const val SCALE = 8
    }
}

class PcOfficeTextRealizer(
    private val extractor: com.point.core.flow.OfficeTextExtractor,
    private val outbox: Outbox,
) : Realizer {
    override val capabilityId = com.point.core.flow.capabilities.OfficeCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult = runCatching {
        val text = extractor.extractText(input)
        if (text.isBlank()) {

            return ActionResult.Failure(
                "В этом документе текста не нашлось — старые .doc и .xls компьютер не открывает",
                recoverable = false,
            )
        }
        val file = File.createTempFile("pc-office-", ".txt").apply { writeText(text) }
        ActionResult.Success(
            com.point.core.model.ResultObject(
                type = ObjectKind.TEXT,
                mime = "text/plain",
                uri = ScratchRef(file.absolutePath),
                metadata = mapOf("name" to "Текст документа"),
            ),
        )
    }.getOrElse { ActionResult.Failure("Текст не достался — документ повреждён или это не офисный файл", recoverable = true) }
}

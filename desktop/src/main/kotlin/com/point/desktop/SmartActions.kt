package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.asFeature
import com.point.core.flow.asMetaKey
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

    // Исследование — знание на исходнике, а не пользовательское действие (см. PcEntitiesRealizer
    // ниже). Без этого флага она оставалась кликабельной кнопкой поверх уже готового ответа
    // (autoInvestigate уже запускает её сама при получении объекта) и лишним дублем «на ПК»
    // уезжала на телефон через advertisedActions() — разбор скрина владельца без live-теста.
    override val meta = CapabilityMeta(priority = 25, investigation = true)
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

    // «Голое время это никогда не дата, это мусор» (#651).
    val meaningful = found.filterNot { it.type == EntityType.DATE_TIME && com.point.core.flow.bareClock(it.value) }
    val metadata = buildMap {
        meaningful.groupBy { it.type }.forEach { (type, list) ->
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
        features = meaningful.mapNotNull { it.type.asFeature() }.toSet(),
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

// «Понять»/«Перевести»/«Спросить AI» на компьютере убраны (#701, решение владельца
// «Убрать, ПК — только исполнитель»): результат для человека был тот же самый, что
// и на телефоне, — устройство не становится отдельной способностью ради самого
// исполнителя (Конституция, «Capability / Realizer»).

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

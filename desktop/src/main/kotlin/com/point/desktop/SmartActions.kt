package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.asFeature
import com.point.core.flow.asMetaKey
import com.point.core.flow.QR_FAILED
import com.point.core.flow.QR_NO_TEXT
import com.point.core.flow.QR_TOO_LONG
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
    // Имя общее с телефоном (#840): работа одна — найти значения в тексте, — и Point
    // не должен считать её двумя разными вопросами только потому, что исполнители разные.
    override val id = com.point.core.flow.KnownCapabilities.ENTITIES
    override val icon = "find"

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
    override val capabilityId = com.point.core.flow.KnownCapabilities.ENTITIES

    // «Найти в тексте» — исследование: результат — знание на исходнике, а не новый
    // объект-отчёт (Конституция §4; аудит 2026-08-09, блок 1.1).
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult = runCatching {
        val text = File(input.uri.value).takeIf(File::isFile)?.readText()
            ?: return ActionResult.Failure("Файла объекта нет на диске", recoverable = false)
        val found = com.point.core.flow.plausibleEntities(extractor.extract(text), text)

        // Одна воронка на обе поверхности (#1139, #1144): та же, что у телефона, — кандидат,
        // проверка формы, sameFact, fullerReading, main/.more/.alt, происхождение от самого
        // объекта. Прежде компьютер держал упрощённую копию: побеждало первое значение по
        // тексту, спор прочтений не замечался, а происхождение жёстко звалось «прочитано» —
        // даже у набранного руками текста.
        val delta = com.point.core.flow.entityDelta(input, found, text)

        // Правила знания написаны один раз и работают на обоих устройствах (#935).
        //
        // Сумма, показание, координаты, квитанция и номер накладной разбираются правилами из
        // `:core:flow`. Телефон их звал, компьютер — нет, и счёт на 12 500 грн оставался на
        // ПК без суммы: движок сущностей о деньгах знает («Суммы — 1»), а ключа знания у него
        // для них нет.
        val byRules = com.point.core.flow.amountFacts(text) +
            com.point.core.flow.meterFacts(text) +
            com.point.core.flow.geoFacts(text) +
            com.point.core.flow.receiptFacts(text) +
            com.point.core.flow.trackFacts(text) +
            com.point.core.flow.serialFacts(text)

        if (found.isEmpty() && byRules.isEmpty()) {

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
        ActionResult.Done(summaryLine(found, byRules), entityFindings(delta, byRules))
    }.getOrElse { ActionResult.Failure("Разобрать текст не вышло — попробуйте ещё раз", recoverable = true) }

    private fun entityFindings(
        delta: com.point.core.model.Findings,
        byRules: Map<String, String>,
    ): com.point.core.model.Findings {
        val answered = mapOf(
            com.point.core.flow.investigationKey(capabilityId) to
                if (delta.metadata.keys.any { it.startsWith(com.point.core.flow.META_ENTITY_PREFIX) } ||
                    byRules.isNotEmpty()
                ) {
                    // Нашли хоть что-то — вопрос отвечен: «не найдено» при найденной сумме
                    // было бы ложью о знании (Конституция §13).
                    com.point.core.flow.InvestigationState.FOUND.wire
                } else {
                    com.point.core.flow.InvestigationState.NOT_FOUND.wire
                },
        )
        return delta.copy(metadata = byRules + delta.metadata + answered)
    }

    private fun summaryLine(found: List<Entity>, byRules: Map<String, String>): String = when {
        found.isEmpty() -> "Нашёл: " + com.point.core.flow.knowledgeRows(byRules)
            .joinToString(", ") { it.name.lowercase() }
        else -> "Нашёл: " + entitySummary(found)
    }
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
        if (text.isEmpty()) return ActionResult.Failure(QR_NO_TEXT, recoverable = false)
        // Потолок и слова отказа — общие с телефоном (#1084): раньше компьютер держал свои сто
        // с небольшим байт и отвергал текст, который телефон кодировал.
        val matrix = qrMatrix(text) ?: return ActionResult.Failure(QR_TOO_LONG, recoverable = false)
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
    }.getOrElse {
        // Длина сюда больше не попадает — её ловит общий потолок выше (#1084): здесь остаётся
        // только сбой самой операции, и валить его на текст было бы неправдой. Слова — те же,
        // что и на телефоне, и лежат они там же, где общий потолок.
        ActionResult.Failure(QR_FAILED, recoverable = true)
    }

    private companion object {

        const val SCALE = 8
    }
}

/**
 * Текст офисного документа на компьютере: знание самого документа (#995) и настоящая причина
 * отказа (#997) — современная .xlsx больше не слышит про старые .doc и .xls, а таблицу читает
 * свой читатель, которому не нужен общий словарь строк.
 */
class PcOfficeTextRealizer(
    private val extractor: com.point.core.flow.OfficeTextExtractor,
    private val outbox: Outbox,
) : Realizer {
    override val capabilityId = com.point.core.flow.capabilities.OfficeCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult = runCatching {
        val text = extractor.extractText(input)
        if (text.isBlank()) {

            return ActionResult.Failure(
                com.point.core.flow.officeTextMissingReason(input.metadata["name"], input.mime),
                recoverable = false,
            )
        }
        val file = File.createTempFile("pc-office-", ".txt").apply { writeText(text) }
        ActionResult.Done(
            com.point.core.flow.capabilities.TEXT_IS_WITH_DOCUMENT,
            com.point.core.model.Findings(
                features = setOf(com.point.core.model.Feature.HAS_TEXT),
                metadata = mapOf(com.point.core.flow.META_OCR_TEXT_REF to file.absolutePath),
            ),
        )
    }.getOrElse { ActionResult.Failure("Текст не достался — документ повреждён или это не офисный файл", recoverable = true) }
}

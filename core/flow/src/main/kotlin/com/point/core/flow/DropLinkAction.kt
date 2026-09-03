package com.point.core.flow

import com.point.core.flow.capabilities.DropLinkCapability
import com.point.core.model.ActionResult
import com.point.core.model.Findings
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Relation
import com.point.core.model.RelationType
import com.point.core.model.ScratchRef
import java.io.File

/** Знание «объект выложен по ссылке»: адрес и срок — на самом объекте (#1071). */
const val META_DROP_LINK = "drop.link"

const val META_DROP_UNTIL = "drop.until"

/**
 * «Дать ссылку» — один исполнитель на телефон и компьютер (#1379).
 *
 * До переноса компьютер держал свою копию: та же выгрузка тем же органом [DropLink], но ссылка
 * уходила только в буфер и знанием объекта не становилась. Телефон делал её знанием (#1071):
 * адрес и срок на самом объекте, рядом — узел ссылки, из которого её можно открыть, показать
 * QR или переслать. Теперь так на обоих; компьютер сверху кладёт ссылку в буфер — это его
 * орган [onLink], а не второе поведение.
 *
 * [keeper] — куда положить саму ссылку файлом: телефон в рабочую копию, компьютер во временный.
 * [meta] — как исполнитель зовётся очереди: компьютер объявляет себя облачным, как и раньше.
 */
class DropLinkRealizer(
    private val drop: DropLink,
    private val keeper: TextKeeper,
    private val onLink: (String) -> Unit = {},
    override val meta: RealizerMeta = RealizerMeta(),
) : Realizer {
    override val capabilityId = DropLinkCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        val file = File(input.uri.value).takeIf(File::isFile)
            ?: return ActionResult.Failure("Файла объекта нет на диске", recoverable = false)
        val name = outboundFileName(input.metadata["name"], input.mime)

        reportStage("Загружаю файл")
        val link = when (val outcome = drop.give(file.absolutePath, name, input.mime)) {
            is DropOutcome.Given -> outcome.link
            // Отказ называет то, что произошло, и ровно одно (#1284): причину знает тот, кто
            // ходил наружу, — она и доезжает.
            is DropOutcome.Refused -> return ActionResult.Failure(outcome.why, recoverable = true)
        }

        val ref = runCatching { keeper.keep(input, link) }.getOrNull()
            ?: return ActionResult.Failure("Ссылка получена, но не сохранилась на устройстве", recoverable = true)
        onLink(link)

        val until = untilTomorrow()
        val linkNode = PointObject(
            id = input.id + ":drop-link",
            mime = "text/uri-list",
            uri = ScratchRef(ref),
            state = ObjectState(ObjectKind.URL),
            metadata = mapOf(
                "name" to "ссылка на $name",
                "entity.url" to link,
                "drop.expires" to "сутки",
            ),
            sourceObjects = listOf(input.id),
            creatorAction = DropLinkCapability.ID.value,
        )
        return ActionResult.Done(
            "Выложено до $until — ссылка у объекта, живёт сутки",
            Findings(
                metadata = mapOf(
                    META_DROP_LINK to link,
                    META_DROP_UNTIL to until,
                ),
                objects = listOf(linkNode),
                relations = listOf(Relation(linkNode.id, RelationType.DERIVED_FROM, input.id)),
            ),
        )
    }

    /** До какого момента ссылка живёт — словами человека, не миллисекундами. */
    private fun untilTomorrow(): String = stampLabel(System.currentTimeMillis() + 24L * 60 * 60 * 1000)
}

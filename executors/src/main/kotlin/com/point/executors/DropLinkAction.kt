package com.point.executors

import com.point.core.flow.capabilities.DropLinkCapability
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.DropLink
import com.point.core.flow.Latency
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.isFileBacked
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import java.io.File
import javax.inject.Inject

class DropLinkRealizer @Inject constructor(
    private val store: ObjectStore,
    private val drop: DropLink,
) : Realizer {
    override val capabilityId = DropLinkCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        val file = File(input.uri.value)

        // Имя наружу — по общему правилу выхода (#1126): экранная обрезка («…») в настоящее
        // имя не попадает, scratch-идентификатор — тем более.
        val name = com.point.core.flow.outboundFileName(input.metadata["name"], input.mime)

        reportStage("Загружаю файл")
        // Отказ называет то, что произошло, и ровно одно (#1284): список «либо это, либо
        // то» — не причина, а перечисление догадок, из которых человеку нечего выбрать.
        // Правда известна тому, кто ходил наружу, — оттуда она и приходит.
        val link = when (val outcome = drop.give(file.absolutePath, name, input.mime)) {
            is com.point.core.flow.DropOutcome.Given -> outcome.link
            is com.point.core.flow.DropOutcome.Refused ->
                return ActionResult.Failure(outcome.why, recoverable = true)
        }

        val ref = store.newScratchFile("txt")
        File(ref.value).writeText(link)

        // «Выложен» — знание об объекте (#1071, решение владельца): ссылка и срок ложатся
        // на исходник, видны на нём и копируются заново. Узел ссылки рождается находкой —
        // в него можно войти. Экрана-реестра нет; отзыва раньше срока нет — сутки истекают
        // сами, это и есть граница.
        val until = untilTomorrow()
        val linkNode = PointObject(
            id = input.id + ":drop-link",
            mime = "text/uri-list",
            uri = ref,
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
            "Выложено до $until — ссылка у объекта",
            com.point.core.model.Findings(
                metadata = mapOf(
                    META_DROP_LINK to link,
                    META_DROP_UNTIL to until,
                ),
                objects = listOf(linkNode),
                relations = listOf(
                    com.point.core.model.Relation(
                        linkNode.id,
                        com.point.core.model.RelationType.DERIVED_FROM,
                        input.id,
                    ),
                ),
            ),
        )
    }

    /** До какого момента ссылка живёт — словами человека, не миллисекундами. */
    private fun untilTomorrow(): String =
        com.point.core.flow.stampLabel(System.currentTimeMillis() + 24L * 60 * 60 * 1000)
}

/** Знание «объект выложен по ссылке»: адрес и срок — на самом объекте (#1071). */
const val META_DROP_LINK = "drop.link"

const val META_DROP_UNTIL = "drop.until"

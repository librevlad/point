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

/**
 * Результат — **новый объект-ссылка**, а не сообщение.
 *
 * Так ссылка попадает в обычную работу Point: её можно скопировать, отправить в мессенджер или
 * показать QR-кодом человеку рядом — теми действиями, которые у URL уже есть. Показать её тостом
 * значило бы заставить человека переписывать ссылку руками.
 */
class DropLinkRealizer @Inject constructor(
    private val store: ObjectStore,
    private val drop: DropLink,
) : Realizer {
    override val capabilityId = DropLinkCapability.ID

    /**
     * Одна стадия, и она про самое важное (#288).
     *
     * «Дать ссылку» объявлено [Latency.SLOW] и сетевым, поэтому забирает экран целиком — и до сих
     * пор человек смотрел там на голый счётчик секунд, пока по сети уезжал его файл. Ровно в этом
     * ожидании вопрос «не зависло ли?» стоит дороже всего: файл уже в дороге, а отменить его
     * человек ещё может.
     *
     * Разбить загрузку честно нечем — контракт [DropLink] отдаёт готовую ссылку одним вызовом и о
     * своём ходе молчит, — и выдумывать проценты мы не станем. Но назвать работу, которая правда
     * идёт, — уже правда: тот же случай и то же решение, что у «Распаковать».
     */
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        val file = File(input.uri.value)
        val name = input.metadata["name"]?.takeIf { it.isNotBlank() } ?: file.name

        reportStage("Загружаю файл")
        val link = drop.give(file.absolutePath, name, input.mime)
            ?: return ActionResult.Failure(
                "Ссылку выдать не удалось — нет связи с сервером или файл слишком большой",
                recoverable = true,
            )

        val ref = store.newScratchFile("txt")
        File(ref.value).writeText(link)
        return ActionResult.Success(
            ResultObject(
                type = ObjectKind.URL,
                mime = "text/uri-list",
                uri = ref,
                metadata = mapOf(
                    "name" to "ссылка на $name",
                    "entity.url" to link,
                    // Срок сказан рядом со ссылкой: человек должен знать, что она не вечная,
                    // до того как отправит её кому-то.
                    "drop.expires" to "сутки",
                ),
            ),
        )
    }
}

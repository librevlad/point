package com.point.executors

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
 * «Дать ссылку» (#388) — отдать файл человеку, у которого Point не стоит.
 *
 * Самая маленькая форма Drop: ни аккаунтов, ни страниц, ни отдельного сервера. Получатель
 * открывает ссылку браузером и получает файл; ссылка живёт сутки и умирает сама.
 *
 * Действие сетевое и **не бесплатное по приватности**: в отличие от всего остального, что возит
 * релей, файл по ссылке лежит на сервере открытым — ключа у чужого человека нет. Поэтому оно
 * стоит за явным тапом и названо прямо.
 */
class DropLinkCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "link"
    override val meta = CapabilityMeta(
        priority = 35,
        cost = Cost.FREE,
        latency = Latency.SLOW,
        network = true,
    )

    override fun label(state: ObjectState) = "Дать ссылку"

    /**
     * Ссылке ссылку не дают (#457).
     *
     * Объект-URL — это сорок байт текста со ссылкой внутри, и «Дать ссылку» загрузило бы на
     * сервер **их**: человек получил бы ссылку на ссылку, а тот, кому он её отправит, — текстовый
     * файлик вместо страницы. Это единственное действие, чей собственный результат (`produces`
     * = URL) снова попадал в его же `accepts`: петля, у которой второй виток бессмыслен.
     *
     * Исключение то же самое, что уже стоит у «Открыть» и «Открыть в…»: у ссылки свои действия
     * («Открыть ссылку», «Скопировать», «Код»), и подменять их загрузкой на сервер незачем.
     */
    override fun accepts(state: ObjectState) =
        state.kind.isFileBacked && state.kind != ObjectKind.URL

    override fun produces(state: ObjectState) = ObjectState(ObjectKind.URL)

    companion object { val ID = CapabilityId("drop-link") }
}

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

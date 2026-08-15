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

    // Согласие на публикацию не спрашивают там, где публикация заведомо невозможна (#1022):
    // дверь помечена недоступной с настоящей причиной ещё до тапа.
    override fun isAvailable(): Boolean = runCatching { drop.canGive() }.getOrDefault(true)

    override fun unavailableReason(): String? =
        if (isAvailable()) null else com.point.core.flow.NOT_SIGNED_IN_TO_GIVE_LINKS

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        val file = File(input.uri.value)
        val name = input.metadata["name"]?.takeIf { it.isNotBlank() } ?: file.name

        // Что известно заранее — говорится точно, а не догадкой (#1022).
        if (!file.isFile) {
            return ActionResult.Failure("Файла объекта нет на диске", recoverable = false)
        }
        if (file.length() > com.point.core.flow.MAX_DROP_BYTES) {
            return ActionResult.Failure("Файл слишком большой, чтобы выложить его по ссылке", recoverable = false)
        }

        reportStage("Загружаю файл")
        val link = drop.give(file.absolutePath, name, input.mime)
            ?: return ActionResult.Failure(
                "Ссылку выдать не удалось — попробуйте ещё раз",
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

                    "drop.expires" to "сутки",
                ),
            ),
        )
    }
}

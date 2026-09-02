package com.point.executors

import com.point.core.flow.Box
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.CropEvidence
import com.point.core.flow.CropPurpose
import com.point.core.flow.EvidenceCropper
import com.point.core.flow.GraphState
import com.point.core.flow.Latency
import com.point.core.flow.META_SELECTION_IDS
import com.point.core.flow.META_SELECTION_PAGE
import com.point.core.flow.META_SELECTION_REGION
import com.point.core.flow.META_SELECTION_SOURCE
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.focusOf
import com.point.core.flow.regionWire
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Взять фрагмент» — действие над областью, а не кнопка внутри инструмента (#742).
 *
 * Экран выделения заменён на Focus (#736): «✓ сверху — единственное завершение». Кнопка
 * «Взять фрагмент» ушла вместе с прежним экраном, и два завершения вернуть нельзя — человек
 * снова выбирал бы устройство результата вместо намерения.
 *
 * Поэтому рождение объекта из области стало обычным действием: показал область — вернулся к
 * объекту — действие появилось рядом с остальными. Focus остаётся сигналом (ADR-0001 §10), а
 * новый объект приходит через Capability и Realizer, как любой другой.
 */
class TakeFragmentCapability @Inject constructor() : Capability {

    override val id = ID

    override val icon = "crop"

    override val meta = CapabilityMeta(priority = 12, latency = Latency.FAST)

    override fun label(state: ObjectState) = "Взять фрагмент"

    /** Форма объекта: вырезать можно из снимка. */
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE

    /** Без Focus действия нет: брать нечего, пока область не показана. */
    override fun accepts(graph: GraphState): Boolean = accepts(graph.state) && graph.focus?.region != null

    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)

    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("take-fragment") }
}

class TakeFragmentRealizer @Inject constructor(
    private val cropper: EvidenceCropper,
    private val store: ObjectStore,
    private val angle: com.point.core.flow.UprightAngle,
) : Realizer {

    override val capabilityId = TakeFragmentCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            val region = focusOf(input.metadata, input.id)?.region
                ?: return@withContext ActionResult.Failure("Не видно, какую область брать", recoverable = false)

            runCatching {
                // Область режется из файла как есть, а камера могла записать его повёрнутым:
                // без этого числа фрагмент выходил на боку, хотя выделяли ровный кадр (#1389).
                val image = cropper.crop(
                    CropEvidence(
                        input.uri.value,
                        region,
                        uprightDegrees = angle.degreesOf(input.uri.value),
                        purpose = CropPurpose.GLANCE,
                    ),
                ) ?: return@withContext ActionResult.Failure("Не удалось вырезать область", recoverable = true)

                val ref = store.newScratchFile(image.extension)
                File(ref.value).writeBytes(image.bytes)
                ActionResult.Success(
                    ResultObject(
                        ObjectKind.IMAGE,
                        if (image.extension == "png") "image/png" else "image/jpeg",
                        ref,
                        origin(input, region),
                    ),
                )
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось взять фрагмент", recoverable = true) }
        }

    /**
     * Знание области остаётся при новом объекте (приёмка #742.3): откуда он вырезан и какая
     * это была часть страницы. Без этого фрагмент теряет связь с исходником в тот же миг,
     * когда рождается.
     */
    private fun origin(input: PointObject, region: Box): Map<String, String> = buildMap {
        put(META_SELECTION_SOURCE, input.id)
        put(META_SELECTION_REGION, regionWire(region))
        put(META_SELECTION_PAGE, "0")
        focusOf(input.metadata, input.id)?.atomIds
            ?.takeIf { it.isNotEmpty() }
            ?.let { put(META_SELECTION_IDS, it.joinToString(" ")) }
    }
}

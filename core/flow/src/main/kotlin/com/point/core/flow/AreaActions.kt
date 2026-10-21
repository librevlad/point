package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.tr
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Взять фрагмент» — действие над областью, а не кнопка внутри инструмента (#742) — одна
 * способность на телефон и компьютер (#1379, волна 2).
 *
 * Экран выделения заменён на Focus (#736): «✓ сверху — единственное завершение». Кнопка
 * «Взять фрагмент» ушла вместе с прежним экраном, и два завершения вернуть нельзя — человек
 * снова выбирал бы устройство результата вместо намерения.
 *
 * Поэтому рождение объекта из области стало обычным действием: показал область — вернулся к
 * объекту — действие появилось рядом с остальными. Focus остаётся сигналом (ADR-0001 §10), а
 * новый объект приходит через Capability и Realizer, как любой другой.
 *
 * Своего круга (`localOnly`): область показана на экране этого устройства, и соседу её
 * не передать — телефон и компьютер друг другу это действие не объявляют.
 */
class TakeFragmentCapability : Capability {

    override val id = ID

    override val icon = "crop"

    override val meta = CapabilityMeta(priority = 12, latency = Latency.FAST, localOnly = true)

    override fun label(state: ObjectState) = tr("Взять фрагмент")

    /** Форма объекта: вырезать можно из снимка. */
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE

    /** Без Focus действия нет: брать нечего, пока область не показана. */
    override fun accepts(graph: GraphState): Boolean = accepts(graph.state) && graph.focus?.region != null

    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)

    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("take-fragment") }
}

/**
 * «Замазать» — убрать со снимка то, что человек не хотел отдавать (#549), — одна способность
 * на телефон и компьютер (#1379, волна 2).
 *
 * Мажет человек, а не детектор. Автоматический поиск лиц отвергнут владельцем 05.08.2026:
 * он обещал бы безопасность, которой не даёт — не увидит ни номер машины в окне, ни чужую
 * переписку на экране, ни бейдж на груди, — а человек, доверившись, решит, что кадр чистый.
 *
 * Обведённые места приходят через Focus (ADR-0001 §10), как и у «Взять фрагмент» (#742):
 * показал места — вернулся к объекту — действие стоит рядом с остальными. Своего круга по
 * той же причине.
 */
class HideAreaCapability : Capability {

    override val id = ID

    override val icon = "blur"

    override val meta = CapabilityMeta(priority = 13, latency = Latency.FAST, localOnly = true)

    override fun label(state: ObjectState) = tr("Замазать")

    /** Форма объекта: замазывают снимок. */
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE

    /** Без показанных мест действия нет: замазывать нечего, пока человек не обвёл. */
    override fun accepts(graph: GraphState): Boolean =
        accepts(graph.state) && graph.focus?.places?.isNotEmpty() == true

    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)

    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("hide-area") }
}

/**
 * Вырезать показанную область отдельным снимком. Органы — у каждой стороны свои: телефон режет
 * Bitmap-ом, компьютер — ImageIO; правило, что взять и что знать о новом объекте, — здесь.
 */
class TakeFragmentRealizer(
    private val cropper: EvidenceCropper,
    private val store: ObjectStore,
    private val angle: UprightAngle,
) : Realizer {

    override val capabilityId = TakeFragmentCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            val region = focusOf(input.metadata, input.id)?.region
                ?: return@withContext ActionResult.Failure(tr("Не видно, какую область брать"), recoverable = false)

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
                ) ?: return@withContext ActionResult.Failure(tr("Не удалось вырезать область"), recoverable = true)

                val ref = store.newScratchFile(image.extension)
                File(ref.value).writeBytes(image.bytes)
                ActionResult.Success(
                    ResultObject(
                        ObjectKind.IMAGE,
                        if (image.extension == "png") "image/png" else "image/jpeg",
                        ref,
                        fragmentOrigin(input.id, input.metadata, region),
                    ),
                )
            }.getOrElse { ActionResult.Failure(it.message ?: tr("Не удалось взять фрагмент"), recoverable = true) }
        }
}

/** Замазать обведённые места. Как читать и писать пиксели, решает [ImageRedactor] стороны. */
class HideAreaRealizer(
    private val redactor: ImageRedactor,
    private val store: ObjectStore,
) : Realizer {

    override val capabilityId = HideAreaCapability.ID

    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            val places = focusOf(input.metadata, input.id)?.places.orEmpty()
            if (places.isEmpty()) {
                return@withContext ActionResult.Failure(tr("Не видно, что замазывать"), recoverable = false)
            }

            runCatching {
                val image = redactor.hide(input.uri.value, places)
                    ?: return@withContext ActionResult.Failure(tr("Не удалось замазать снимок"), recoverable = true)

                val ref = store.newScratchFile(image.extension)
                File(ref.value).writeBytes(image.bytes)
                ActionResult.Success(
                    ResultObject(
                        ObjectKind.IMAGE,
                        if (image.extension == "png") "image/png" else "image/jpeg",
                        ref,
                        hiddenOrigin(input.id, places),
                    ),
                )
            }.getOrElse { ActionResult.Failure(it.message ?: tr("Не удалось замазать снимок"), recoverable = true) }
        }
}

/** Что на снимке закрыто — «l t r b» через `;` в координатах исходника (#549). */
const val META_HIDDEN_PLACES = "hidden.places"

/**
 * Откуда фрагмент и какая это была часть снимка — знание нового объекта (приёмка #742.3).
 * Одно правило на оба устройства: без него фрагмент теряет связь с исходником в тот же миг,
 * когда рождается.
 */
fun fragmentOrigin(sourceId: String, metadata: Map<String, String>, region: Box): Map<String, String> = buildMap {
    put(META_SELECTION_SOURCE, sourceId)
    put(META_SELECTION_REGION, regionWire(region))
    put(META_SELECTION_PAGE, "0")
    focusOf(metadata, sourceId)?.atomIds
        ?.takeIf { it.isNotEmpty() }
        ?.let { put(META_SELECTION_IDS, it.joinToString(" ")) }
}

/** Откуда снимок и что на нём закрыто — знание нового объекта, как и у фрагмента (#742). */
fun hiddenOrigin(sourceId: String, places: List<Box>): Map<String, String> = buildMap {
    put(META_SELECTION_SOURCE, sourceId)
    put(META_HIDDEN_PLACES, partsWire(places))
    places.reduce(Box::union).let { put(META_SELECTION_REGION, regionWire(it)) }
}

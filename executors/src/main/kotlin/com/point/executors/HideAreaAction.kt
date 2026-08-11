package com.point.executors

import com.point.core.flow.Box
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.GraphState
import com.point.core.flow.ImageRedactor
import com.point.core.flow.Latency
import com.point.core.flow.META_SELECTION_REGION
import com.point.core.flow.META_SELECTION_SOURCE
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.focusOf
import com.point.core.flow.partsWire
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
 * «Замазать» — убрать со снимка то, что человек не хотел отдавать (#549).
 *
 * Мажет человек, а не детектор. Автоматический поиск лиц отвергнут владельцем 05.08.2026:
 * он обещал бы безопасность, которой не даёт — не увидит ни номер машины в окне, ни чужую
 * переписку на экране, ни бейдж на груди, — а человек, доверившись, решит, что кадр чистый.
 *
 * Обведённые места приходят через Focus (ADR-0001 §10), как и у «Взять фрагмент» (#742):
 * показал места — вернулся к объекту — действие стоит рядом с остальными.
 */
class HideAreaCapability @Inject constructor() : Capability {

    override val id = ID

    override val icon = "blur"

    override val meta = CapabilityMeta(priority = 13, latency = Latency.FAST)

    override fun label(state: ObjectState) = "Замазать"

    /** Форма объекта: замазывают снимок. */
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE

    /** Без показанных мест действия нет: замазывать нечего, пока человек не обвёл. */
    override fun accepts(graph: GraphState): Boolean =
        accepts(graph.state) && graph.focus?.places?.isNotEmpty() == true

    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)

    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("hide-area") }
}

class HideAreaRealizer @Inject constructor(
    private val redactor: ImageRedactor,
    private val store: ObjectStore,
) : Realizer {

    override val capabilityId = HideAreaCapability.ID

    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            val places = focusOf(input.metadata, input.id)?.places.orEmpty()
            if (places.isEmpty()) {
                return@withContext ActionResult.Failure(NOTHING_SHOWN, recoverable = false)
            }

            runCatching {
                val image = redactor.hide(input.uri.value, places)
                    ?: return@withContext ActionResult.Failure(NOT_HIDDEN, recoverable = true)

                val ref = store.newScratchFile(image.extension)
                File(ref.value).writeBytes(image.bytes)
                ActionResult.Success(
                    ResultObject(
                        ObjectKind.IMAGE,
                        if (image.extension == "png") "image/png" else "image/jpeg",
                        ref,
                        origin(input, places),
                    ),
                )
            }.getOrElse { ActionResult.Failure(it.message ?: NOT_HIDDEN, recoverable = true) }
        }

    /** Откуда снимок и что на нём закрыто — знание нового объекта, как и у фрагмента (#742). */
    private fun origin(input: PointObject, places: List<Box>): Map<String, String> = buildMap {
        put(META_SELECTION_SOURCE, input.id)
        put(META_HIDDEN_PLACES, partsWire(places))
        places.reduce(Box::union).let { put(META_SELECTION_REGION, regionWire(it)) }
    }

    private companion object {

        const val NOTHING_SHOWN = "Не видно, что замазывать"

        const val NOT_HIDDEN = "Не удалось замазать снимок"
    }
}

/** Что на снимке закрыто — «l t r b» через `;` в координатах исходника (#549). */
const val META_HIDDEN_PLACES = "hidden.places"

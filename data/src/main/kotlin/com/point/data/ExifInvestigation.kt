package com.point.data

import android.media.ExifInterface
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.META_ENTITY_GEO
import com.point.core.flow.META_SHOT_AT
import com.point.core.flow.Realizer
import com.point.core.flow.shotDateLabel
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.Findings
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Что снимок знает о себе (#547): когда снят и где.
 *
 * EXIF читался и раньше — но только ради поворота кадра перед распознаванием, а остальное
 * выбрасывалось. Человек шёл узнавать дату съёмки в файловый менеджер, хотя это ровно «что я
 * могу узнать об этом объекте».
 *
 * Съёмочная кухня (модель камеры, выдержка, диафрагма) не читается: для человека с объектом
 * это шум.
 */
class ExifInvestigation @Inject constructor() : Capability {

    override val id = ID

    override val icon = ""

    override val meta = CapabilityMeta(
        investigation = true,
        latency = Latency.FAST,
        mayYield = setOf(Feature.HAS_SHOT_AT, Feature.HAS_GEO),
    )

    override fun label(state: ObjectState) = ""

    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE

    override fun produces(state: ObjectState) = state

    companion object {

        val ID = com.point.core.model.CapabilityId("exif")
    }
}

class ExifInvestigationRealizer @Inject constructor() : Realizer {

    override val capabilityId = ExifInvestigation.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching { findings(input) }.fold(
            onSuccess = { ActionResult.Done("", it) },
            onFailure = { ActionResult.Failure(it.message ?: "исследование не удалось", recoverable = true) },
        )

    private suspend fun findings(obj: PointObject): Findings = withContext(Dispatchers.IO) {
        val file = File(obj.uri.value).takeIf { it.isFile } ?: return@withContext Findings()
        val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull() ?: return@withContext Findings()

        val shot = shotDateLabel(
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME),
        )
        val place = runCatching {
            FloatArray(2).takeIf { exif.getLatLong(it) }?.let { "%.5f, %.5f".format(it[0], it[1]) }
        }.getOrNull()

        Findings(
            features = buildSet {
                if (shot != null) add(Feature.HAS_SHOT_AT)
                if (place != null) add(Feature.HAS_GEO)
            },
            metadata = buildMap {
                shot?.let { put(META_SHOT_AT, it) }
                place?.let { put(META_ENTITY_GEO, it) }
            },
        )
    }
}

package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.KnownCapabilities
import com.point.core.flow.Latency
import com.point.core.flow.META_ENTITY_GEO
import com.point.core.flow.META_SHOT_AT
import com.point.core.flow.ObjectStore
import com.point.core.flow.PHOTO_HEAD_BYTES
import com.point.core.flow.PhotoHead
import com.point.core.flow.Realizer
import com.point.core.flow.cleanedPhotoName
import com.point.core.flow.photoHead
import com.point.core.flow.reportStage
import com.point.core.flow.shotDateLabel
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.Findings
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Что снимок знает о себе — тот же вопрос, что телефон задаёт `ExifInterface` (#547, #1379).
 *
 * Без него снимок, брошенный в окно компьютера, оставался немым: дата и место лежали в файле,
 * а «Очистить метаданные» не появлялось — действию не на что было опереться. Исследование
 * дешёвое и локальное: компьютер задаёт его сам, едва объект пришёл.
 */
class PcPhotoHeadCapability : Capability {
    override val id = KnownCapabilities.PHOTO_HEAD
    override val icon = ""
    override val meta = CapabilityMeta(
        investigation = true,
        latency = Latency.FAST,
        mayYield = setOf(Feature.HAS_SHOT_AT, Feature.HAS_GEO),
    )
    override fun label(state: ObjectState) = ""
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = state
}

class PcPhotoHeadRealizer : Realizer {
    override val capabilityId = KnownCapabilities.PHOTO_HEAD

    override val meta = com.point.core.flow.RealizerMeta(actor = "exif")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        com.point.core.flow.investigated { withContext(Dispatchers.IO) { findings(input) } }

    private fun findings(input: PointObject): Findings {
        val head = photoHeadOf(File(input.uri.value)) ?: return Findings()
        val shot = shotDateLabel(head.shotAt)

        // Точка — всегда точкой: запятая русской локали превратила бы координаты в четыре числа.
        val place = if (head.latitude != null && head.longitude != null) {
            "%.5f, %.5f".format(java.util.Locale.ROOT, head.latitude, head.longitude)
        } else {
            null
        }
        return Findings(
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

/**
 * «Очистить метаданные» на компьютере (#1379, волна 2): пиксели переписываются заново, и ни
 * одна служебная запись вместе со снимком не переезжает.
 *
 * Кадр сначала ставится, как его сняли: метка поворота уходит вместе с остальными записями, и
 * без разворота портретный снимок вышел бы лежащим на боку.
 */
class PcCleanMetadataRealizer(private val store: ObjectStore) : Realizer {
    override val capabilityId = com.point.core.flow.CleanMetadataCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage(tr("Читаю снимок"))
                val source = File(input.uri.value)
                val image = source.takeIf(File::isFile)?.let { ImageIO.read(it) }
                    ?: return@withContext ActionResult.Failure(
                        com.point.core.flow.readerFailure(
                            com.point.core.flow.READER_NOT_DECODED,
                            input.state.kind,
                        ),
                        recoverable = false,
                    )

                reportStage(tr("Убираю служебные записи"))
                val upright = uprightOpaque(image, photoHeadOf(source)?.uprightDegrees ?: 0)
                val ref = store.newScratchFile("jpg")
                writeJpeg(upright, File(ref.value))

                ActionResult.Success(
                    ResultObject(
                        ObjectKind.IMAGE,
                        "image/jpeg",
                        ref,
                        mapOf("op" to "clean-metadata", "name" to cleanedPhotoName(input.metadata["name"])),
                    ),
                )
            }.getOrElse { ActionResult.Failure(NOT_CLEANED, recoverable = true) }
        }

    /** Качество то же, что у телефона: очищены записи, а не содержимое. */
    private fun writeJpeg(image: BufferedImage, target: File) {
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            compressionQuality = QUALITY
        }
        ImageIO.createImageOutputStream(target).use { out ->
            writer.output = out
            writer.write(null, javax.imageio.IIOImage(image, null, null), params)
        }
        writer.dispose()
    }

    private companion object {
        val NOT_CLEANED: String get() = tr("Очистить не вышло — этот формат снимка компьютер не открывает")
        const val QUALITY = 0.95f
    }
}

internal fun photoHeadOf(file: File): PhotoHead? = runCatching {
    val start = file.inputStream().use { it.readNBytes(PHOTO_HEAD_BYTES) }
    photoHead(start)
}.getOrNull()

/** Кадр, повёрнутый как его сняли, на белом: JPEG прозрачности не держит. */
internal fun uprightOpaque(image: BufferedImage, degrees: Int): BufferedImage {
    val angle = ((degrees % 360) + 360) % 360
    val swap = angle == 90 || angle == 270
    val w = if (swap) image.height else image.width
    val h = if (swap) image.width else image.height
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.color = java.awt.Color.WHITE
    g.fillRect(0, 0, w, h)
    g.translate(w / 2.0, h / 2.0)
    g.rotate(Math.toRadians(angle.toDouble()))
    g.drawImage(image, -image.width / 2, -image.height / 2, null)
    g.dispose()
    return out
}

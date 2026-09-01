package com.point.desktop

import com.point.core.flow.AiFact
import com.point.core.flow.AiFacts
import com.point.core.flow.AiOutcome
import com.point.core.flow.CloudPrivacySettings
import com.point.core.flow.CropEvidence
import com.point.core.flow.CropPurpose
import com.point.core.flow.EvidenceCropper
import com.point.core.flow.EvidenceImage
import com.point.core.flow.FrameForModel
import com.point.core.flow.InlineFrame
import com.point.core.flow.ObjectStore
import com.point.core.flow.PAGE_KINDS
import com.point.core.flow.PdfTextExtractor
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.Realizer
import com.point.core.flow.UserAiKey
import com.point.core.flow.UserAiKeys
import com.point.core.flow.UserKeyStore
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * «В Excel» исполняется на компьютере (#1369, решение владельца 01.09.2026: «файловые
 * действия работают там, где человек»).
 *
 * Само действие — общее и живёт в `:core:flow` (`ExcelRealizer`): у компьютера здесь только
 * его местные органы — файлы, кадр для модели и ключи из конфига. Ключи те же, что человек
 * вписал на телефоне: круг устройств обменивается ими сам (`exchangeSecrets`, #610).
 *
 * Решение #701 («ПК — только исполнитель») остаётся в силе для действий, чей результат —
 * знание: «Понять», «Перевести», «Спросить AI» на компьютере не появляются. Здесь другое:
 * результат — файл, а файл выходит в месте — как бумага у печати (ADR-0001 §7).
 */

/** Набор страниц на компьютере в таблицу пока не сшивается: его детей здесь читать нечем. */
class PcExcelRealizer(private val inner: Realizer) : Realizer by inner {
    override fun accepts(state: ObjectState): Boolean = state.kind in PAGE_KINDS
}

/**
 * Файлы результата — в своей папке компьютера, по тому же сроку, что и всё принятое:
 * старое убирает `forgetAbandoned` при запуске.
 */
class PcScratchStore(private val dir: File) : ObjectStore {
    override suspend fun newScratchFile(extension: String): ScratchRef {
        dir.mkdirs()
        return ScratchRef(File.createTempFile("point-", ".$extension", dir).absolutePath)
    }

    override suspend fun readText(obj: PointObject, limit: Int): String =
        runCatching { File(obj.uri.value).takeIf(File::isFile)?.readText()?.take(limit) }
            .getOrNull().orEmpty()

    override suspend fun children(collection: PointObject, limit: Int) =
        com.point.core.flow.CollectionContent<PointObject>(emptyList(), 0)

    override suspend fun ingest(sourceUri: String, mime: String): PointObject =
        error("на компьютере объект принимает Inbox")

    override suspend fun ingestMultiple(sources: List<String>): PointObject =
        error("на компьютере объект принимает Inbox")

    override suspend fun put(
        result: ResultObject,
        from: PointObject?,
        by: com.point.core.model.CapabilityId?,
    ): PointObject = error("на компьютере объект принимает Inbox")

    override suspend fun clear() = Unit
}

/**
 * Кадр для модели: тот же смысл, что у телефона (#1239), — снимок ужимается до стороны
 * модели и уезжает одной строкой. Без кадра ключ человека сгорал бы на запросе без картинки.
 */
object PcModelFrames : FrameForModel {
    private const val MAX_EDGE_PX = 3072
    private const val JPEG_QUALITY = 0.9f
    private const val MAX_INLINE_BYTES = 15L * 1024 * 1024

    override fun of(path: String, mime: String): InlineFrame? {
        if (!mime.startsWith("image/")) return null
        return runCatching {
            val source = ImageIO.read(File(path)) ?: return null
            val fitted = bounded(source, MAX_EDGE_PX)

            // Прозрачность живёт только в PNG; остальное едет JPEG — он меньше.
            val png = fitted.colorModel.hasAlpha()
            val bytes = encode(fitted, png) ?: return null
            if (bytes.size > MAX_INLINE_BYTES) return null
            InlineFrame(Base64.getEncoder().encodeToString(bytes), if (png) "image/png" else "image/jpeg")
        }.getOrNull()
    }

    private fun bounded(source: BufferedImage, maxEdge: Int): BufferedImage {
        val edge = maxOf(source.width, source.height)
        if (edge <= maxEdge) return source
        val scale = maxEdge.toDouble() / edge
        val w = maxOf(1, (source.width * scale).toInt())
        val h = maxOf(1, (source.height * scale).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g.drawImage(source, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    private fun encode(image: BufferedImage, png: Boolean): ByteArray? {
        val out = ByteArrayOutputStream()
        if (png) {
            if (!ImageIO.write(image, "png", out)) return null
            return out.toByteArray()
        }
        val opaque = if (image.type == BufferedImage.TYPE_INT_RGB) {
            image
        } else {
            BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also {
                val g = it.createGraphics()
                g.drawImage(image, 0, 0, java.awt.Color.WHITE, null)
                g.dispose()
            }
        }
        val writer = ImageIO.getImageWritersByFormatName("jpg").asSequence().firstOrNull() ?: return null
        val params = writer.defaultWriteParam.apply {
            compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            compressionQuality = JPEG_QUALITY
        }
        javax.imageio.stream.MemoryCacheImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.write(null, javax.imageio.IIOImage(opaque, null, null), params)
        }
        writer.dispose()
        return out.toByteArray()
    }
}

/**
 * Вырезка спорной области — то же, что делает телефон Bitmap-ом (#1109): прямоугольник в
 * пикселях кадра, поворот до вертикали, JPEG. Зовётся только при атомном слое — то есть для
 * объектов, приехавших со знанием чтения.
 */
class PcEvidenceCrops : EvidenceCropper {
    override suspend fun crop(evidence: CropEvidence): EvidenceImage? = runCatching {
        val source = ImageIO.read(File(evidence.imagePath)) ?: return null
        val region = evidence.region
        val left = region.left.toInt().coerceIn(0, source.width - 1)
        val top = region.top.toInt().coerceIn(0, source.height - 1)
        val right = kotlin.math.ceil(region.right).toInt().coerceIn(left + 1, source.width)
        val bottom = kotlin.math.ceil(region.bottom).toInt().coerceIn(top + 1, source.height)
        val cut = source.getSubimage(left, top, right - left, bottom - top)
        val upright = turned(cut, evidence.uprightDegrees)
        val reading = evidence.purpose == CropPurpose.READING
        val bytes = jpeg(upright, if (reading) 0.95f else 0.9f) ?: return null
        EvidenceImage(bytes, upright.width, upright.height, "jpg")
    }.getOrNull()

    private fun turned(image: BufferedImage, degrees: Int): BufferedImage {
        val angle = ((degrees % 360) + 360) % 360
        if (angle == 0) return image
        val swap = angle == 90 || angle == 270
        val w = if (swap) image.height else image.width
        val h = if (swap) image.width else image.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.translate(w / 2.0, h / 2.0)
        g.rotate(Math.toRadians(angle.toDouble()))
        g.drawImage(image, -image.width / 2, -image.height / 2, null)
        g.dispose()
        return out
    }

    private fun jpeg(image: BufferedImage, quality: Float): ByteArray? {
        val out = ByteArrayOutputStream()
        val opaque = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also {
            val g = it.createGraphics()
            g.drawImage(image, 0, 0, java.awt.Color.WHITE, null)
            g.dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpg").asSequence().firstOrNull() ?: return null
        val params = writer.defaultWriteParam.apply {
            compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        javax.imageio.stream.MemoryCacheImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.write(null, javax.imageio.IIOImage(opaque, null, null), params)
        }
        writer.dispose()
        return out.toByteArray()
    }
}

/** Текстовый слой PDF — тем же pdfbox, что и «Извлечь текст» (#631). */
class PcPdfTextKnowledge : PdfTextExtractor {
    override suspend fun extractText(obj: PointObject, atMost: Int?): String =
        runCatching {
            File(obj.uri.value).takeIf(File::isFile)?.let { PdfBoxText().of(it) }
        }.getOrNull().orEmpty().let { if (atMost != null) it.take(atMost) else it }
}

/**
 * Ключи человека на компьютере — те же, что в настройках и у круга (#610): правда живёт
 * в файле конфига, здесь только чтение и запись в него.
 */
class PcUserKeys(private val pointDir: File) : UserKeyStore {
    override fun keys(): UserAiKeys = FilePcConfig(pointDir).load().aiKeys

    override suspend fun save(key: UserAiKey) {
        val config = FilePcConfig(pointDir)
        config.save(config.load().let { it.copy(aiKeys = it.aiKeys.with(key)) })
    }

    override suspend fun forget(providerId: String) {
        val config = FilePcConfig(pointDir)
        config.save(config.load().let { it.copy(aiKeys = it.aiKeys.without(providerId)) })
    }

    override suspend fun clear() {
        val config = FilePcConfig(pointDir)
        config.save(config.load().copy(aiKeys = UserAiKeys()))
    }
}

/** Исходы сервисов — память процесса: на экран компьютера они пока не выходят. */
class PcAiFacts : AiFacts {
    private val known = java.util.concurrent.ConcurrentHashMap<String, AiFact>()
    override fun all(): Map<String, AiFact> = known.toMap()
    override fun remember(providerId: String, outcome: AiOutcome) {
        known[providerId] = AiFact(outcome, System.currentTimeMillis())
    }
}

/** Режим «куда можно отправлять» — тот же, что закрывает дорогу наружу объявлению (#1269). */
class PcCloudPrivacy(private val pointDir: File) : CloudPrivacySettings {
    override fun level(): PrivacyLevel = FilePcConfig(pointDir).load().privacy
    override suspend fun setLevel(level: PrivacyLevel) {
        val config = FilePcConfig(pointDir)
        config.save(config.load().copy(privacy = level))
    }
}

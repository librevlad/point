package com.point.bot

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.common.HybridBinarizer
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A generic LLM action for the bot (#92): the whole family (Понять/Перевести/Собрать
 * данные) is one capability shape differing only by prompt and accepted kinds. Paid/network.
 */
class LlmBotCapability(
    private val idValue: String,
    private val title: String,
    private val kinds: Set<ObjectKind>,
    private val priority: Int,
) : Capability {
    override val id = CapabilityId(idValue)
    override val icon = "ai"
    override val meta = CapabilityMeta(priority = priority, cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = title
    override fun accepts(state: ObjectState) = state.kind in kinds
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
}

class LlmBotRealizer(
    idValue: String,
    private val prompt: String,
    private val llm: LlmClient,
) : Realizer {
    override val capabilityId = CapabilityId(idValue)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            val extra = if (input.state.kind == ObjectKind.TEXT) {
                "\n\n" + File(input.uri.value).readText().take(MAX_TEXT)
            } else {
                ""
            }
            ActionResult.Success(llm.run(input, prompt + extra))
        }.getOrElse { ActionResult.Failure(it.message ?: "AI недоступен", recoverable = true) }

    private companion object { const val MAX_TEXT = 20_000 }
}

/** text/url → QR PNG (pure zxing, no LLM) — a local action the bot runs instantly. */
class QrMakeCapability : Capability {
    override val id = CapabilityId("qr-make")
    override val icon = "qr"
    override val meta = CapabilityMeta(priority = 40)
    override fun label(state: ObjectState) = "Сделать QR"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT || state.kind == ObjectKind.URL
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)
}

class QrMakeRealizer(private val scratchDir: File) : Realizer {
    override val capabilityId = CapabilityId("qr-make")
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult = withContext(Dispatchers.IO) {
        runCatching {
            val text = File(input.uri.value).readText().trim().ifBlank { return@withContext ActionResult.Failure("Пустой текст", recoverable = true) }
            // UTF-8 hint writes an ECI marker so Cyrillic survives the decode round-trip.
            val hints = mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8")
            val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512, hints)
            val out = File(scratchDir.apply { mkdirs() }, "qr-${System.nanoTime()}.png")
            MatrixToImageWriter.writeToPath(matrix, "PNG", out.toPath())
            ActionResult.Success(ResultObject(ObjectKind.IMAGE, "image/png", ScratchRef(out.absolutePath), mapOf("name" to "qr.png")))
        }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось сделать QR", recoverable = true) }
    }
}

/** image → decoded QR text (pure zxing). */
class QrReadCapability : Capability {
    override val id = CapabilityId("qr-read")
    override val icon = "qr-scan"
    override val meta = CapabilityMeta(priority = 20)
    override fun label(state: ObjectState) = "Считать QR"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
}

class QrReadRealizer(private val scratchDir: File) : Realizer {
    override val capabilityId = CapabilityId("qr-read")
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult = withContext(Dispatchers.IO) {
        runCatching {
            val image = ImageIO.read(File(input.uri.value)) ?: return@withContext ActionResult.Failure("Не удалось прочитать изображение", recoverable = true)
            val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))
            val text = MultiFormatReader().decode(bitmap).text
            val out = File(scratchDir.apply { mkdirs() }, "qr-text-${System.nanoTime()}.txt")
            out.writeText(text)
            ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(out.absolutePath)))
        }.getOrElse { ActionResult.Failure("QR-код не найден", recoverable = true) }
    }
}

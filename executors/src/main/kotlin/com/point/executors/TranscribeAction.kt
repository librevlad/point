package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.flow.SpeechReadiness
import com.point.core.flow.SpeechToText
import com.point.core.flow.Transcription
import com.point.core.flow.labelNeedingKey
import com.point.core.flow.listeningStage
import com.point.core.flow.reportStage
import com.point.core.flow.speechKeyRefusal
import com.point.core.flow.transcriptFileText
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class TranscribeCapability @Inject constructor(
    private val readiness: SpeechReadiness,
) : Capability {
    override val id = ID
    override val icon = "transcribe"

    override val meta = CapabilityMeta(
        priority = 10,
        cost = Cost.PAID,
        latency = Latency.SLOW,
        network = true,
        auth = true,
    )

    override fun label(state: ObjectState) =
        labelNeedingKey("Расшифровать", readiness.missingKeys().isEmpty())

    override fun accepts(state: ObjectState) = state.kind == ObjectKind.AUDIO
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    companion object { val ID = com.point.core.flow.KnownCapabilities.TRANSCRIBE }
}

class TranscribeRealizer @Inject constructor(
    private val store: ObjectStore,
    private val speech: SpeechToText,
    private val readiness: SpeechReadiness,
) : Realizer {
    override val capabilityId = TranscribeCapability.ID
    override val meta = RealizerMeta(priority = 10, kind = RealizerKind.CLOUD)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {

            val needs = readiness.missingKeys()
            if (needs.isNotEmpty()) {
                return@withContext ActionResult.Failure(speechKeyRefusal(needs), recoverable = true)
            }

            reportStage(listeningStage(input.mime, sizeOf(input), input.metadata["name"]))

            val heard = runCatching { speech.transcribe(input) }
                .getOrElse { return@withContext ActionResult.Failure(
                    it.message ?: "Не удалось расшифровать запись",
                    recoverable = true,
                ) }

            when (heard) {
                is Transcription.Silence ->
                    ActionResult.Failure("В записи не слышно речи", recoverable = false)

                is Transcription.Heard -> runCatching {
                    // Расшифровка — обычный текст, а не размеченный документ (#873):
                    // разделов в ней больше нет, и открывается она проще.
                    val ref = store.newScratchFile("txt")
                    File(ref.value).writeText(transcriptFileText(heard))
                    ActionResult.Success(
                        ResultObject(
                            ObjectKind.TEXT,
                            "text/plain",
                            ref,
                            buildMap {
                                put("op", "transcribe")
                                put("name", "Расшифровка")
                                if (heard.summary.isNotBlank()) {
                                    put(META_SEMANTIC_SUMMARY, heard.summary.take(120))
                                }
                            },
                        ),
                    )
                }.getOrElse {
                    ActionResult.Failure(it.message ?: "Ошибка записи результата", recoverable = true)
                }
            }
        }

    private fun sizeOf(input: PointObject): Long =
        runCatching { File(input.uri.value).length() }.getOrDefault(0L)
}

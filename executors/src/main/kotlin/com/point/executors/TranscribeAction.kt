package com.point.executors

import com.point.core.flow.AudioLevel
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.flow.NO_SPEECH_HEARD
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.flow.SpeechReadiness
import com.point.core.flow.SpeechToText
import com.point.core.flow.Transcription
import com.point.core.flow.labelNeedingKey
import com.point.core.flow.listeningStage
import com.point.core.flow.nothingToHear
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

    // Расшифровка — знание/представление той же записи, а не новый объект (#1097).
    override fun produces(state: ObjectState) = state.with(com.point.core.model.Feature.HAS_TEXT)

    override fun yields(state: ObjectState) =
        com.point.core.model.ActionYield.Same("слова записи · запись уйдёт в сервис")

    // Понимание записи, каким и было: produces больше не TEXT, а intent остался прежним.
    override fun intents(state: ObjectState) = setOf(com.point.core.model.Intent.UNDERSTAND)

    companion object { val ID = com.point.core.flow.KnownCapabilities.TRANSCRIBE }
}

class TranscribeRealizer @Inject constructor(
    private val store: ObjectStore,
    private val speech: SpeechToText,
    private val readiness: SpeechReadiness,
    private val level: AudioLevel,
) : Realizer {
    override val capabilityId = TranscribeCapability.ID
    override val meta = RealizerMeta(priority = 10, kind = RealizerKind.CLOUD)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {

            // Слушаем сами — прежде и сервиса, и разговора про ключи (#1053). На пустой
            // записи Whisper не молчит, а выдумывает фразу: двадцать секунд цифровой тишины
            // возвращались как «Thank you.», и выдумка ложилась знанием об объекте. Пустоту
            // телефон слышит без облака и бесплатно, поэтому ответ про такую запись человек
            // получает и без единого ключа — отказывать ему ключами там, где ответ уже есть,
            // значило бы прятать готовое знание за настройками.
            // Не измерили — не «тихо»: такая запись идёт дальше обычным путём.
            if (nothingToHear(runCatching { level.peak(input) }.getOrNull())) {
                return@withContext heardNothing()
            }

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
                // Сервис отработал и ответил «речи нет» — это тот же ответ, что телефон
                // слышит сам, и исход у него тот же (#1274, решение владельца 23.08.2026).
                is Transcription.Silence -> heardNothing()

                is Transcription.Heard -> runCatching {
                    // Расшифровка — знание той же записи, а не новый объект (#1097, GRF-006):
                    // слова ложатся представлением на исходник тем же ключом, каким лежит
                    // любое чтение, и экран записи показывает их своим блоком «Текст».
                    val ref = store.newScratchFile("txt")
                    File(ref.value).writeText(transcriptFileText(heard))
                    ActionResult.Done(
                        "Расшифровано — слова у записи",
                        com.point.core.model.Findings(
                            features = setOf(com.point.core.model.Feature.HAS_TEXT),
                            metadata = buildMap {
                                put(com.point.core.flow.META_OCR_TEXT_REF, ref.value)
                                put(
                                    com.point.core.flow.investigationKey(TranscribeCapability.ID),
                                    com.point.core.flow.InvestigationState.FOUND.wire,
                                )
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

    /**
     * «Не нашлось» — знание, а не сбой (Конституция §13).
     *
     * Речи в записи нет — это ответ на заданный вопрос, и вопрос расшифровки закрывается
     * как «смотрели — не нашлось», а не остаётся открытым до следующего тапа.
     *
     * Один исход на обе тишины (#1274): услышал её телефон сам или ответил сервис — знание
     * об этой записи одно и то же, и человек не платит второй раз за тот же ответ.
     */
    private fun heardNothing(): ActionResult = ActionResult.Done(
        NO_SPEECH_HEARD,
        com.point.core.model.Findings(
            metadata = mapOf(
                com.point.core.flow.investigationKey(TranscribeCapability.ID) to
                    com.point.core.flow.InvestigationState.NOT_FOUND.wire,
            ),
        ),
    )

    private fun sizeOf(input: PointObject): Long =
        runCatching { File(input.uri.value).length() }.getOrDefault(0L)
}

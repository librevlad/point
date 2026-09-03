package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.Findings
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * «Расшифровать» — одна способность на телефон и компьютер (#1379).
 *
 * До переноса компьютер держал свою копию: `PcTranscribeCapability` без вопроса о ключе и
 * `PcTranscribeRealizer` с собственным клиентом сервиса внутри. Здесь — то, что было у
 * телефона: название само говорит, что нужен ключ, а движок речи и его готовность стоят за
 * швами [SpeechToText] и [SpeechReadiness]. Что подставить в швы, решает каждая сторона сама.
 */
class TranscribeCapability(
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

    override fun produces(state: ObjectState) = state.with(Feature.HAS_TEXT)

    override fun yields(state: ObjectState) = com.point.core.model.ActionYield.Same(SPEECH_PROMISE)

    override fun intents(state: ObjectState) = setOf(com.point.core.model.Intent.UNDERSTAND)

    companion object { val ID = KnownCapabilities.TRANSCRIBE }
}

/**
 * Расшифровка записи — знание той же записи, а не новый объект (#1097, GRF-006).
 *
 * Один исполнитель на обе стороны (#1379). Компьютер раньше слушал запись тем же правилом
 * (#1053, #1312), но своей копией: две копии одного правила расходятся молча. [keeper] — куда
 * положить слова: телефон кладёт в рабочую копию, компьютер — во временный файл.
 */
class TranscribeRealizer(
    private val speech: SpeechToText,
    private val readiness: SpeechReadiness,
    private val level: AudioLevel,
    private val keeper: TextKeeper,
) : Realizer {
    override val capabilityId = TranscribeCapability.ID

    override val meta = RealizerMeta(priority = 10, kind = RealizerKind.CLOUD)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            // Слушаем сами — прежде и сервиса, и разговора про ключи (#1053). На пустой
            // записи Whisper не молчит, а выдумывает фразу: двадцать секунд цифровой тишины
            // возвращались как «Thank you.», и выдумка ложилась знанием об объекте. Пустоту
            // устройство слышит без облака и бесплатно, поэтому ответ про такую запись человек
            // получает и без единого ключа — отказывать ему ключами там, где ответ уже есть,
            // значило бы прятать готовое знание за настройками.
            // Не измерили — не «тихо»: такая запись идёт дальше обычным путём.
            if (nothingToHear(runCatching { level.peak(input) }.getOrNull())) {
                return@withContext heardNothing(measuredHere = true)
            }

            val needs = readiness.missingKeys()
            if (needs.isNotEmpty()) {
                return@withContext ActionResult.Failure(speechKeyRefusal(needs), recoverable = true)
            }

            reportStage(listeningStage(input.mime, sizeOf(input), input.metadata["name"]))
            val heard = runCatching { speech.transcribe(input) }
                .getOrElse {
                    return@withContext ActionResult.Failure(
                        it.message ?: "Не удалось расшифровать запись",
                        recoverable = true,
                    )
                }

            when (heard) {
                // Сервис отработал и ответил «речи нет» — это тот же ответ, что устройство
                // слышит само, и исход у него тот же (#1274, решение владельца 23.08.2026).
                // Знание о самой записи он при этом не приносит: сказано про речь, не про
                // звук, — и следующий исполнитель за ним ещё может услышать (#1054).
                is Transcription.Silence -> heardNothing(measuredHere = false)
                is Transcription.Heard -> {
                    // Слова ложатся представлением на исходник тем же ключом, каким лежит
                    // любое чтение, и экран записи показывает их своим блоком «Текст».
                    val ref = runCatching { keeper.keep(input, transcriptFileText(heard)) }.getOrNull()
                        ?: return@withContext ActionResult.Failure("Ошибка записи результата", recoverable = true)
                    ActionResult.Done(
                        // Слова о сделанном — из общего словаря (#1097): ту же работу делает
                        // компьютер, и звучать она обязана одинаково.
                        SPEECH_IS_KNOWLEDGE,
                        Findings(
                            features = setOf(Feature.HAS_TEXT),
                            metadata = buildMap {
                                put(META_OCR_TEXT_REF, ref)
                                put(investigationKey(TranscribeCapability.ID), InvestigationState.FOUND.wire)
                                if (heard.summary.isNotBlank()) put(META_SEMANTIC_SUMMARY, heard.summary.take(120))
                            },
                        ),
                    )
                }
            }
        }

    /**
     * «Не нашлось» — знание, а не сбой (Конституция §13).
     *
     * Речи в записи нет — это ответ на заданный вопрос, и вопрос расшифровки закрывается
     * как «смотрели — не нашлось», а не остаётся открытым до следующего нажатия.
     *
     * Один исход на обе тишины (#1274): услышало её устройство само или ответил сервис —
     * знание об этой записи одно и то же, и человек не платит второй раз за тот же ответ.
     *
     * Различаются они не исходом, а тем, что узнано о самой записи (#1053).
     * [measuredHere] — устройство разобрало запись до сэмплов и намерило тишину: это знание о
     * содержимом, и оно едет в Graph вместе с ответом. Оттого измеренная тишина закрывает и
     * очередь исполнителей: следующему достанутся те же байты, и уступать ему нечего —
     * иначе запись уезжала бы на компьютер и оттуда в сервис ровно за той выдумкой, ради
     * которой её и слушали. Ответ сервиса про речь такого знания о записи не даёт.
     */
    private fun heardNothing(measuredHere: Boolean): ActionResult = ActionResult.Done(
        NO_SPEECH_HEARD,
        Findings(
            metadata = buildMap {
                if (measuredHere) put(META_AUDIO_SILENT, "true")
                put(investigationKey(TranscribeCapability.ID), InvestigationState.NOT_FOUND.wire)
            },
        ),
    )

    private fun sizeOf(input: PointObject): Long =
        runCatching { File(input.uri.value).length() }.getOrDefault(0L)
}

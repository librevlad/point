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
import com.point.core.flow.SpeechToText
import com.point.core.flow.Transcription
import com.point.core.flow.listeningStage
import com.point.core.flow.reportStage
import com.point.core.flow.transcriptMarkdown
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

/**
 * Голосовое → текст, у которого суть уже сверху (#223).
 *
 * Скоуп решён владельцем: человек кинул в Point голосовуху из мессенджера и должен получить и
 * дословный текст, и короткую суть — чтобы не слушать три минуты ради одной фразы.
 *
 * **Почему суть здесь, а не вторым действием.** Point никогда не строит автоматические цепочки:
 * цепочка — это когда приложение само запускает **следующее** действие, которого человек не
 * выбирал. Сколько работы делает **одно** действие внутри себя — его собственный договор, и
 * договор этот здесь: один тап, один запрос к модели, один объект, в котором сначала суть,
 * потом расшифровка. «Распознать текст» точно так же делает внутри себя больше одного шага.
 *
 * Сеть — только после тапа: [CapabilityMeta.network] держит расшифровку вне первого экрана,
 * и до выбора человека ни одного байта записи никуда не уезжает.
 */
class TranscribeCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "transcribe"

    /**
     * [Latency.SLOW] — не осторожность, а правда о работе: минута записи слушается моделью
     * дольше, чем идёт первый экран. Объявленная долгой работа уходит на экран ожидания, где
     * видно, что действие делает сейчас, и где живёт кнопка отмены (#288); объявленная быстрой
     * молчала бы на объекте.
     */
    override val meta = CapabilityMeta(
        priority = 10,
        cost = Cost.PAID,
        latency = Latency.SLOW,
        network = true,
        auth = true,
    )

    override fun label(state: ObjectState) = "Расшифровать"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.AUDIO
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    companion object { val ID = CapabilityId("transcribe") }
}

/**
 * Единственный сегодня реализатор расшифровки. Движок — за контрактом [SpeechToText], поэтому
 * завтрашний офлайновый (whisper.cpp, системный API, если такой появится) встаёт сюда, ничего
 * не меняя ни в UI, ни в графе.
 *
 * Три исхода, и все три названы словами:
 * - речь услышана → TEXT-объект (суть + дословный текст), суть заодно ложится в
 *   `semantic.summary`, и экран объекта показывает её подписью — тем же местом, что у «Понять»;
 * - речи нет → **невосстановимый** отказ: повтор тишину не расшифрует;
 * - движок не дошёл (нет ключа, нет сети, формат не читается) → восстановимый отказ его
 *   собственными словами. Проглотить его пустым текстом было бы ложью, неотличимой от успеха.
 */
class TranscribeRealizer @Inject constructor(
    private val store: ObjectStore,
    private val speech: SpeechToText,
) : Realizer {
    override val capabilityId = TranscribeCapability.ID
    override val meta = RealizerMeta(priority = 10, kind = RealizerKind.CLOUD)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            // Длинная запись не заставляет гадать, зависло ли: стадия говорит, сколько примерно
            // длится запись и что это займёт время. Оценка честно приблизительная (#223).
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
                    val ref = store.newScratchFile("md")
                    File(ref.value).writeText(transcriptMarkdown(heard))
                    ActionResult.Success(
                        ResultObject(
                            ObjectKind.TEXT,
                            "text/markdown",
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

    /** Вес записи — на нём держится оценка длительности. Файла может не быть (объект пришёл
     *  ссылкой, которую не скопировали) — тогда 0, и о минутах никто не заикнётся. */
    private fun sizeOf(input: PointObject): Long =
        runCatching { File(input.uri.value).length() }.getOrDefault(0L)
}

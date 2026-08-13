package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.NO_VOICE_TEXT
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.flow.Spoken
import com.point.core.flow.TextToSpeech
import com.point.core.flow.languageOfText
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Прочитать текст вслух и отдать записью (#442).
 *
 * Длинную статью или расшифровку удобнее слушать за рулём. Сегодня для этого ставят чтеца
 * с подпиской, хотя голос уже стоит в самом телефоне: он бесплатен, работает без сети и
 * потому приватен по умолчанию.
 *
 * Результат — обычный объект-запись, у которого уже есть «Сохранить», «Поделиться» и «На
 * компьютер»: новых дверей под него заводить не нужно.
 */
class SpeakCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "transcribe"

    override val meta = CapabilityMeta(
        priority = 8,
        cost = Cost.FREE,
        latency = Latency.SLOW,
        network = false,
    )

    override fun label(state: ObjectState) = "Озвучить"

    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.AUDIO)

    companion object { val ID = CapabilityId("speak") }
}

class SpeakRealizer @Inject constructor(
    private val store: ObjectStore,
    private val voice: TextToSpeech,

    /**
     * Чтение вслух подчиняется тому же режиму, что и всё уходящее наружу (#924, решение
     * владельца 13.08.2026): закрытый режим — только голос, читающий на устройстве;
     * открытый — любой, включая лучший серверный.
     */
    private val privacy: com.point.core.flow.CloudPrivacySettings,
) : Realizer {
    override val capabilityId = SpeakCapability.ID
    override val meta = RealizerMeta(priority = 10, kind = RealizerKind.LOCAL)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {

            val text = runCatching { File(input.uri.value).readText() }.getOrNull().orEmpty().trim()
            if (text.isEmpty()) {
                return@withContext ActionResult.Failure("Читать нечего — в объекте нет текста", recoverable = false)
            }
            if (voice.voices().isEmpty()) {
                return@withContext ActionResult.Failure(NO_VOICE_TEXT, recoverable = true)
            }

            reportStage(READING)
            val ref = store.newScratchFile("wav")
            // Длинный текст читается кусками. Молчаливое ожидание на статье в тридцать тысяч
            // знаков выглядит как зависание, поэтому стадия называет, где мы.
            val level = runCatching { privacy.level() }
                .getOrDefault(com.point.core.flow.PrivacyLevel.DEFAULT)
            val onDeviceOnly = !com.point.core.flow.allowedAt(level, com.point.core.flow.AI_CHAIN_PRIVACY)
            val said = voice.speak(text, languageOfText(text), ref.value, onDeviceOnly) { done, all ->
                if (all > 1) reportStage("$READING — $done из $all")
            }

            when (said) {
                is Spoken.Refused -> ActionResult.Failure(said.why, recoverable = true)
                is Spoken.Done -> ActionResult.Success(
                    ResultObject(
                        ObjectKind.AUDIO,
                        said.mime,
                        com.point.core.model.ScratchRef(said.path),
                        mapOf("op" to "speak", "name" to nameFor(input)),
                    ),
                )
            }
        }

    /** Запись называется по исходному тексту: в списке объектов их будет два рядом. */
    private fun nameFor(input: PointObject): String {
        val was = input.metadata["name"].orEmpty().substringBeforeLast('.').trim()
        return (was.takeIf { it.isNotBlank() } ?: "Текст") + " — вслух.wav"
    }

    private companion object {
        const val READING = "Читаю вслух"
    }
}

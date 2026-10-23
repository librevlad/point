package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.Lang
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Переводчик на самом устройстве (#433, решение владельца 23.10.2026): без ключа, а после
 * первого скачивания языка — и без сети. Текст устройства не покидает.
 */
interface OnDeviceTranslator {

    /**
     * Перевести [text] на [target].
     *
     * `null` — язык текста не узнан или такой пары у переводчика нет: это не срыв, а «не мне».
     * Срыв (язык не скачался) — исключение со словами для человека.
     */
    suspend fun translate(text: String, target: Lang): String?
}

/**
 * Переводит телефон, а не модель, когда модели нет или до неё нельзя (#433): ключа нет или
 * режим приватности не пускает наружу. Ключ есть и режим пускает — переводит модель, как
 * раньше: она переводит лучше, и выбор человека в пользу ключа уже сделан.
 */
fun translatesOnDevice(keySet: Boolean, level: PrivacyLevel): Boolean = !keySet || !anyoneAllowedAt(level)

/** На какой язык просили перевести словами; `null` — на такой телефон сам не переводит. */
internal fun asked(amendment: String): Lang? {
    val words = amendment.lowercase()
    return when {
        "англ" in words || "english" in words -> Lang.EN
        "укр" in words || "ukrain" in words -> Lang.UK
        "рус" in words || "russian" in words -> Lang.RU
        else -> null
    }
}

class OnDeviceTranslateRealizer(
    private val translator: OnDeviceTranslator,
    private val known: CurrentKnowledge,
    private val store: ObjectStore,
    private val onDevice: () -> Boolean,
) : Realizer {
    override val capabilityId = TranslateCapability.ID

    override fun isAvailable(): Boolean = onDevice()

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = known.textOf(input).orEmpty()
                if (text.isBlank()) return@runCatching ActionResult.Failure(tr("Нет текста для перевода"), recoverable = true)

                val target = if (amendment.isNullOrBlank()) translateTargetLang(text, Lang.current()) else asked(amendment)
                    ?: return@runCatching ActionResult.Failure(NO_SUCH_LANGUAGE, recoverable = true)

                reportStage(tr("Перевожу на {0}", languageName(target)))
                val translated = translator.translate(text, target)
                    ?: return@runCatching ActionResult.Failure(NOT_RECOGNISED, recoverable = true)

                val ref = store.newScratchFile("txt")
                File(ref.value).writeText(translated)
                ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ref, mapOf("op" to "translate")))
            }.getOrElse { ActionResult.Failure(it.message ?: tr("Ошибка перевода"), recoverable = true) }
        }

    private companion object {
        val NO_SUCH_LANGUAGE: String get() =
            tr("Без ключа AI телефон переводит на английский, украинский и русский")

        val NOT_RECOGNISED: String get() =
            tr("Язык этого текста телефон сам не узнал — с ключом AI переведёт модель")
    }
}

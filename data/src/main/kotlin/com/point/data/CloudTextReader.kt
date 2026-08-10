package com.point.data

import com.point.core.flow.CloudPrivacySettings
import com.point.core.flow.ExternalEye
import com.point.core.flow.ExternalReading
import com.point.core.flow.NetworkAvailability
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.ReaderPrivacy
import com.point.core.flow.allowedAt
import com.point.core.flow.allowedBy
import com.point.core.model.PointObject
import javax.inject.Inject

interface CloudTextReader {

    val reader: String

    val privacy: ReaderPrivacy

    val configured: Boolean

    fun canRead(obj: PointObject): Boolean = obj.mime.startsWith("image/")

    suspend fun read(obj: PointObject): String
}

class DefaultExternalEye @Inject constructor(
    private val readers: List<@JvmSuppressWildcards CloudTextReader>,
    private val privacy: CloudPrivacySettings,
    private val network: NetworkAvailability,
) : ExternalEye {

    override fun available(): Boolean = eyes().isNotEmpty()

    private fun eyes(): List<CloudTextReader> =
        allowedBy(privacy.level(), readers.filter { it.configured }) { it.privacy }

    override suspend fun read(obj: PointObject): ExternalReading {
        val allowed = eyes()
        if (allowed.isEmpty()) error(nobodyAllowed())
        val errors = mutableListOf<String>()
        var considered = 0
        for (eye in allowed) {
            if (!eye.canRead(obj)) continue
            considered++

            // Перед выходом наружу — спросить телефон, есть ли сеть вообще (#690,
            // #691). Нашёлся хоть один настоящий кандидат для этого объекта, и только
            // тогда: без него идти наружу всё равно было не за чем, а офлайн вся
            // очередь читателей одинаково молчит.
            if (!network.isAvailable()) error(NO_NETWORK)

            try {
                val text = eye.read(obj)
                if (text.isNotBlank()) {
                    return ExternalReading(text, eye.reader, eye.privacy.where, eye.privacy.promise.what)
                }
                errors += "${eye.reader}: страница прочитана пустой"
            } catch (e: Exception) {
                errors += e.message ?: e.javaClass.simpleName
            }
        }
        if (considered == 0) error(NOT_FOR_THIS_OBJECT)
        error(summariseCloudErrors(errors) + keyHint())
    }

    private fun keyHint(): String {
        val skipped = readers.any { !it.configured && allowedAt(privacy.level(), it.privacy) }
        return if (skipped) KEY_HINT else ""
    }

    private fun nobodyAllowed(): String = when (privacy.level()) {
        PrivacyLevel.DEVICE_ONLY -> DEVICE_ONLY
        PrivacyLevel.NO_TRAINING ->
            if (readers.any { it.configured }) STRICT_LEVEL_EMPTY else NOT_CONFIGURED
        PrivacyLevel.FREE_FIRST -> NOT_CONFIGURED
    }

    private companion object {
        const val NOT_CONFIGURED =
            "Чтение снаружи не настроено — задайте бесплатный ключ Mistral (см. настройки)"

        const val DEVICE_ONLY =
            "Наружу ничего не отправляется — в настройках выбрано «Только на телефоне»"

        const val STRICT_LEVEL_EMPTY =
            "Читателей, обещавших не учиться на присланном, сейчас нет — смягчите настройку «Куда можно отправлять»"

        const val NOT_FOR_THIS_OBJECT =
            "Чтение снаружи не берётся за этот объект — читают снимок страницы"

        const val NO_NETWORK =
            "Чтение снаружи недоступно — нет подключения к интернету"

        const val KEY_HINT =
            ". Есть читатель посильнее — он включится, если задать бесплатный ключ Mistral (см. настройки)"
    }
}

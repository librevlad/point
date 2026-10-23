package com.point.data

import com.point.core.flow.CloudPrivacySettings
import com.point.core.flow.ExternalEye
import com.point.core.flow.ExternalReading
import com.point.core.flow.NetworkAvailability
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.ReaderPrivacy
import com.point.core.flow.allowedAt
import com.point.core.flow.allowedBy
import com.point.core.flow.noTextAnswer
import com.point.core.model.PointObject
import com.point.core.model.tr
import com.point.core.flow.AiFact
import com.point.core.flow.AiOutcome
import com.point.core.flow.aiOutcomeOf
import com.point.core.flow.awakeFirst
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import com.point.core.flow.summariseCloudErrors

interface CloudTextReader {

    val reader: String

    val privacy: ReaderPrivacy

    val configured: Boolean

    fun canRead(obj: PointObject): Boolean = obj.mime.startsWith("image/")

    suspend fun read(obj: PointObject): String
}

@Singleton
class DefaultExternalEye(
    private val readers: List<@JvmSuppressWildcards CloudTextReader>,
    private val privacy: CloudPrivacySettings,
    private val network: NetworkAvailability,
    private val now: () -> Long,
) : ExternalEye {

    @Inject constructor(
        readers: List<@JvmSuppressWildcards CloudTextReader>,
        privacy: CloudPrivacySettings,
        network: NetworkAvailability,
    ) : this(readers, privacy, network, System::currentTimeMillis)

    /**
     * Чем кончилось последнее чтение у каждого читателя — память процесса (#433): по ней
     * недавно молчавший идёт в конец очереди. Ключей и пределов читателей человек не видит,
     * поэтому и на экран ключей эта память не выходит.
     */
    private val lastOutcome = ConcurrentHashMap<String, AiFact>()

    override fun available(): Boolean = eyes().isNotEmpty()

    private fun eyes(): List<CloudTextReader> =
        allowedBy(privacy.level(), readers.filter { it.configured }) { it.privacy }

    override suspend fun read(obj: PointObject): ExternalReading {
        val allowed = awakeFirst(eyes(), now()) { lastOutcome[it.reader] }
        if (allowed.isEmpty()) error(nobodyAllowed())
        val errors = mutableListOf<String>()
        var considered = 0
        var sawNoText: ExternalReading? = null
        var failed = false
        for (eye in allowed) {
            if (!eye.canRead(obj)) continue
            considered++

            // Перед выходом наружу — спросить телефон, есть ли сеть вообще (#690,
            // #691). Нашёлся хоть один настоящий кандидат для этого объекта, и только
            // тогда: без него идти наружу всё равно было не за чем, а офлайн вся
            // очередь читателей одинаково молчит.
            if (!network.isAvailable()) error(com.point.core.flow.NO_NETWORK_TEXT)

            try {
                val text = eye.read(obj)
                lastOutcome[eye.reader] = AiFact(AiOutcome.ANSWERED, now())
                if (!noTextAnswer(text)) {
                    return ExternalReading(text, eye.reader, eye.privacy.where, eye.privacy.promise.what)
                }

                // Читатель посмотрел и текста не увидел — пустым листом или служебной
                // пометкой вроде «*[No text detected]*» (#1054). Пометка — не текст и
                // не победа: очередь идёт дальше, следующий может увидеть больше.
                sawNoText = sawNoText ?: ExternalReading("", eye.reader, eye.privacy.where, eye.privacy.promise.what)

                // Идентификатор читалки человеку не адресован (#1259): он остаётся в
                // metadata `engine` у результата, а не в строке на баннере.
                errors += com.point.core.flow.PAGE_READ_EMPTY
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) lastOutcome[eye.reader] = AiFact(aiOutcomeOf(e), now())
                failed = true
                errors += e.message ?: e.javaClass.simpleName
            }
        }
        if (considered == 0) error(NOT_FOR_THIS_OBJECT)

        // Все, кто мог посмотреть, посмотрели и текста не увидели — это ответ, а не срыв:
        // чтение возвращается пустым, и вопрос получает честное «не нашлось» (#1054).
        // Стоило одному сорваться — ответа нет: он мог увидеть то, чего не увидели другие.
        if (sawNoText != null && !failed) return sawNoText

        // Приписка про ключ — только там, где ключ и решает (#1260). Оборвалась связь —
        // приписка советовала идти в настройки и заводить ключ, хотя ключ там ничего не
        // меняет: человек тратил ход впустую. Везде, где сильный читатель мог бы ответить —
        // непринятый ключ, исчерпанный предел, отказ без причины, — про него сказано.
        //
        // Решает ветка сводки, а не сравнение готовой строки: сравнение гасило приписку от
        // любого суффикса и не знало бы про новую ветку (#1260).
        val said = summariseCloudErrors(errors, WHAT_FAILED)
        val keyMayHelp = com.point.core.flow.cloudRefusalKind(errors) !=
            com.point.core.flow.CloudRefusalKind.CONNECTION_LOST
        error(said + if (keyMayHelp) keyHint() else "")
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
        val NOT_CONFIGURED: String get() =
            tr("Чтение снаружи не настроено — задайте бесплатный ключ Mistral (см. настройки)")

        /** Общие слова отказа по режиму (#840): своей копии у читателя нет. */
        val DEVICE_ONLY: String =
            com.point.core.flow.chainClosedBy(com.point.core.flow.PrivacyLevel.DEVICE_ONLY)

        val STRICT_LEVEL_EMPTY: String get() =
            tr("Читателей, обещавших не учиться на присланном, сейчас нет — смягчите настройку «Куда можно отправлять»")

        val NOT_FOR_THIS_OBJECT: String get() =
            tr("Чтение снаружи не берётся за этот объект — читают снимок страницы")

        /** Глагол этой цепочки для общей сводки отказов (#1237). */
        val WHAT_FAILED: String get() = tr("прочитать")

        val KEY_HINT: String get() =
            tr(". Есть читатель посильнее — он включится, если задать бесплатный ключ Mistral (см. настройки)")
    }
}

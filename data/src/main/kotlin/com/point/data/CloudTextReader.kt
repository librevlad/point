package com.point.data

import com.point.core.flow.CloudPrivacySettings
import com.point.core.flow.ExternalEye
import com.point.core.flow.ExternalReading
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.ReaderPrivacy
import com.point.core.flow.allowedBy
import com.point.core.model.PointObject
import javax.inject.Inject

/**
 * Один внешний глаз — чужой сервис, читающий страницу целиком (#280).
 *
 * Зеркало [CloudAtomRecognizer] для случая «текст без геометрии»: те же две вещи, которых
 * телефонному движку знать не нужно (есть ли ключ, берётся ли он за такой объект), плюс третья,
 * которой не было ни у кого, — **куда попадает кадр**. Раньше это решалось за человека при отборе
 * поставщиков; теперь это столбец, который видно, и уровень приватности человек выбирает сам.
 */
interface CloudTextReader {

    /** Имя читателя — оно же уезжает в метаданные результата и в текст отказа. */
    val reader: String

    /** Куда попадает кадр и что с ним там делают. */
    val privacy: ReaderPrivacy

    /** Ключ есть (или не нужен). Без него читателя просто нет — это не сбой. */
    val configured: Boolean

    /** Берётся ли за такой объект. Сегодня все берутся только за снимок. */
    fun canRead(obj: PointObject): Boolean = obj.mime.startsWith("image/")

    /**
     * Текст страницы. **Бросает**, если не дошёл: пустая строка означала бы «страница пустая», а
     * это другая новость.
     */
    suspend fun read(obj: PointObject): String
}

/**
 * Цепочка внешних глаз — первый прочитавший выигрывает (#280).
 *
 * Три правила, и все три — прямые следствия решений владельца:
 * - **порядок по тому, кто лучше читает даром** (замер 04.08.2026), а не по приватности. Приватность
 *   больше никого не выбрасывает: она отбирает по выбранному человеком уровню, не переставляя очередь;
 * - **402/429 — следующий, а не касса.** Тезис проекта — жить на бесплатном;
 * - **все отказали — честный отказ.** Пустой текст неотличим от пустой страницы.
 *
 * Уровень спрашивается на каждом чтении, а не запоминается: человек мог переключить его между двумя
 * снимками, и закэшированное разрешение отправило бы кадр туда, куда уже запретили.
 */
class DefaultExternalEye @Inject constructor(
    private val readers: List<@JvmSuppressWildcards CloudTextReader>,
    private val privacy: CloudPrivacySettings,
) : ExternalEye {

    override fun available(): Boolean = eyes().isNotEmpty()

    /** Кому сейчас можно: настроен И разрешён выбранным уровнем. Порядок сохраняется. */
    private fun eyes(): List<CloudTextReader> =
        allowedBy(privacy.level(), readers.filter { it.configured }) { it.privacy }

    override suspend fun read(obj: PointObject): ExternalReading {
        val allowed = eyes()
        if (allowed.isEmpty()) error(nobodyAllowed())
        val errors = mutableListOf<String>()
        var considered = 0
        for (eye in allowed) {
            if (!eye.canRead(obj)) continue // например, PDF там, где читают только снимок
            considered++
            try {
                val text = eye.read(obj)
                if (text.isNotBlank()) return ExternalReading(text, eye.reader, eye.privacy.where)
                errors += "${eye.reader}: страница прочитана пустой"
            } catch (e: Exception) {
                errors += e.message ?: e.javaClass.simpleName
            }
        }
        if (considered == 0) error(NOT_FOR_THIS_OBJECT)
        error(summariseCloudErrors(errors))
    }

    /**
     * «Читать некому» бывает трёх разных сортов, и путать их нельзя.
     *
     * Человеку, который сам выбрал «только на телефоне», совет «задайте ключ» — не статус, а
     * непонимание: ключ ему не поможет, поможет переключатель. И наоборот.
     */
    private fun nobodyAllowed(): String = when (privacy.level()) {
        PrivacyLevel.DEVICE_ONLY -> DEVICE_ONLY
        PrivacyLevel.EUROPE_ONLY ->
            if (readers.any { it.configured }) EUROPE_ONLY_EMPTY else NOT_CONFIGURED
        PrivacyLevel.FREE_FIRST -> NOT_CONFIGURED
    }

    private companion object {
        const val NOT_CONFIGURED =
            "Чтение снаружи не настроено — задайте бесплатный ключ Mistral (см. настройки)"

        const val DEVICE_ONLY =
            "Наружу ничего не отправляется — в настройках выбрано «Только на телефоне»"

        const val EUROPE_ONLY_EMPTY =
            "Европейских читателей нет — задайте бесплатный ключ Mistral или смягчите настройку «Куда можно отправлять»"

        const val NOT_FOR_THIS_OBJECT =
            "Чтение снаружи не берётся за этот объект — читают снимок страницы"
    }
}

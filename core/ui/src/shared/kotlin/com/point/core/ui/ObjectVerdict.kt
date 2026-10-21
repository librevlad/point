package com.point.core.ui

import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.flow.META_SEMANTIC_TYPE
import com.point.core.flow.META_SIZE
import com.point.core.flow.META_WHOLE_FRAME
import com.point.core.flow.documentLabel
import com.point.core.flow.humanWeight
import com.point.core.flow.recordingLength
import com.point.core.flow.unusableReasonOf
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject

import com.point.core.model.tr
/*
 * Как вещь называется в шапке своего экрана — одно слово на телефон и компьютер.
 *
 * Жило в телефонной части `:core:ui`, и компьютер называл тот же объект по-своему: сводкой модели
 * или голым видом, без «Покупки», «Визитки» и меры записи. Каталог `src/shared/kotlin`
 * компилируют обе стороны (макет окна компьютера 19.10.2026 зовёт вещь тем же заголовком).
 */

data class ObjectVerdict(val headline: String, val subline: String?, val measure: String? = null)

fun objectMeasure(obj: PointObject): String? {
    if (obj.state.kind != ObjectKind.AUDIO) return null

    // Сначала точное знание из самой записи (#1123): прикидка по размеру врала в полтора
    // раза, «примерно» рядом с такой ошибкой — не честность, а маскировка.
    obj.metadata[com.point.core.flow.META_DURATION_SECONDS]?.toLongOrNull()
        ?.let { com.point.core.flow.exactRecordingLength(it) }
        ?.let { return it }
    val size = obj.metadata[META_SIZE]?.toLongOrNull() ?: return null
    return recordingLength(obj.mime, size, obj.metadata["name"]) ?: humanWeight(size)
}

fun objectVerdict(obj: PointObject): ObjectVerdict {
    val state = obj.state
    val headline = when {
        state.has(Feature.IS_PURCHASE) -> tr("Покупка")
        state.has(Feature.IS_MEETING) -> tr("Встреча")
        state.has(Feature.IS_RECIPE) -> tr("Рецепт")
        state.has(Feature.IS_JOB) -> tr("Вакансия")
        state.has(Feature.HAS_VCARD) -> tr("Визитка")

        else -> documentLabel(obj.metadata[META_SEMANTIC_TYPE]) ?: heroKindLabel(obj)
    }

    // Годность — часть состояния объекта (#684/#685): человек читает её здесь же, до
    // первого тапа, — она важнее названия файла или того, что успела сказать модель.
    val unusable = unusableReasonOf(obj.metadata).takeIf { state.has(Feature.UNUSABLE) }

    // Страницу на снимке не нашли, и человеку отдан весь кадр (#1333). Это он читает здесь
    // же: выбеленный кадр целиком выглядит как выбеленная страница, и без этой строки скан
    // «не нашлось» был бы неотличим от удавшегося — а переснять стоит именно такой.
    val wholeFrame = obj.metadata[META_WHOLE_FRAME]?.takeIf { it.isNotBlank() }
    val summary = obj.metadata[META_SEMANTIC_SUMMARY]?.takeIf { it.isNotBlank() }
    val name = obj.metadata["name"]?.takeIf { it.isNotBlank() && it != headline }

    // Найденное зовут его собственным значением (#769). В списке карточка человека
    // подписана именем, а внутри объект назывался «Человек» — вход терял имя, хотя это
    // главное, что о человеке известно. Вид при этом не пропадает: он уходит подписью.
    val own = valueHeadline(obj)
    if (own != null) return ObjectVerdict(own, unusable ?: headline, objectMeasure(obj))
    return ObjectVerdict(headline, unusable ?: wholeFrame ?: summary ?: name, objectMeasure(obj))
}

/**
 * Значение, которым объект зовётся сам: имя человека, номер, дата.
 *
 * Такие узлы рождает понимание, и живут они значением, а не файлом, — поэтому и признак
 * тот же: содержимое лежит прямо в ссылке. У файла имя берётся иначе, его это не трогает.
 */
private fun valueHeadline(obj: PointObject): String? =
    if (obj.uri is com.point.core.model.ValueRef) foundHeadline(obj).takeIf { it.isNotBlank() } else null

/**
 * Подпись героя берётся у того же детектора, что рисует знак (#1233).
 *
 * До этого таблицу узнавали два разных списка форматов, и один экран мог одновременно
 * показать знак «Таблица» и заголовок «Документ».
 */
private fun heroKindLabel(obj: PointObject): String =
    if (kindMarkOf(obj) == KindMark.SPREADSHEET) kindMarkLabel(KindMark.SPREADSHEET) else kindLabel(obj.state.kind)

/**
 * Заголовок найденного объекта — текущее значение его факта, а не `uri`:
 * после правки человеком uri продолжает хранить прежнее значение (ADR-0001 §4 —
 * одно значение в двух ролях; носитель истины — факт).
 */
fun foundHeadline(obj: PointObject): String =
    // Объект-файл в списке найденного — исходник, из которого сделан результат (#925), или
    // страница набора — зовётся своим именем, а не первым найденным в нём фактом (#1421):
    // исходный текст с контактами стоял у результата под именем своей ссылки, как второй
    // узел той же ссылки. Первый факт — лицо узла-значения, не файла.
    obj.metadata["name"]?.takeIf { it.isNotBlank() && !isValueNode(obj) }

        ?: obj.metadata.entries.firstOrNull { (key, _) ->
        (key.startsWith(META_ENTITY_PREFIX) || key.startsWith(com.point.core.flow.META_GRAPH_ROLE_PREFIX)) &&
            !com.point.core.flow.isAnnotationKey(key) && !com.point.core.flow.isStateKey(key)
    }?.takeIf { it.value.isNotBlank() }

        // Номер и здесь показывается по-человечески (#932): чип, заголовок объекта и строка
        // знания говорят об одном номере одним видом — иначе одно и то же значение выглядит
        // на экране двумя разными.
        ?.let { (key, value) -> com.point.core.flow.shownKnowledge(key, value) }

        // Результат чужого исполнителя (ПК) не несёт entity/role-фактов — только имя:
        // без этого запасного шага чип показывал путь до scratch-файла (#681).
        ?: obj.metadata["name"]?.takeIf { it.isNotBlank() }

        // Узел-значение хранит своё значение прямо в ссылке — оно и есть его лицо.
        ?: obj.uri.value.takeIf { obj.uri is com.point.core.model.ValueRef }

        // Совсем без имени — вид объекта: путь на диске к человеку не выходит (#1038, #1100).
        ?: kindMarkLabel(kindMarkOf(obj))

/** Узел-значение: телефон, дата, ссылка — то, что найдено в объекте, а не сам объект-файл. */
internal fun isValueNode(obj: PointObject): Boolean =
    obj.state.kind in com.point.core.flow.EXTRACTED_KINDS || obj.uri is com.point.core.model.ValueRef

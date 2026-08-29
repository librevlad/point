package com.point.core.flow

import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectState

/**
 * Негодный объект не уезжает наружу (#855).
 *
 * Норма записана рядом, в `ObjectFitness`: годность видна «экрану, подписи действия под
 * дверью и `Resolver`'у **до похода в облако**». Телефон её выполнял, компьютер — нет:
 * `DesktopResolver` выбирал из всех кандидатов подряд, хотя `desktop/Inbox.kt` сам же
 * ставит `Feature.UNUSABLE` пустому файлу при приёме.
 *
 * Стоило это не красоты: пустой файл, брошенный в окно на компьютере, уезжал на чужой
 * сервер, чтобы там выяснилось, что читать нечего. Конституция: «Приватность важнее
 * удобства» — объект покидает устройства человека ради дела, а тут дела нет, про
 * негодность уже известно.
 *
 * Поэтому правило живёт здесь, а не копией клаузулы в каждом резолвере — ровно как
 * ранжирование действий после #840.
 */
fun <T> staysHomeWhenUnfit(
    state: ObjectState,
    candidates: List<T>,
    sendsOutward: (T) -> Boolean,
): List<T> = if (state.has(Feature.UNUSABLE)) candidates.filterNot(sendsOutward) else candidates

/**
 * Уедет ли объект наружу через этого исполнителя.
 *
 * Спрашивается объявленный признак `RealizerMeta.leavesCircle`, а не вид исполнителя (#1088):
 * чужой сервис уносит объект наружу по своей природе, а компьютер круга — только тогда, когда
 * сам сказал, что отправит дальше. Пока вопрос задавался виду, компьютеру приходилось звать
 * себя облаком, чтобы согласие не потерялось.
 *
 * Но у части сетевых способностей — «Понять», «Перевести», AI, «Дать ссылку» — единственный
 * исполнитель почему-то назван здешним, хотя внутри зовёт модель или отдаёт байты наружу.
 * Способность здесь надёжнее: `CapabilityMeta.network` объявлен именно там и не разъезжается
 * с реализацией так, как это уже случилось однажды с `RealizerKind`.
 */
fun sendsOutward(realizer: Realizer, capabilityIsNetwork: (CapabilityId) -> Boolean): Boolean =
    realizer.meta.leavesCircle || capabilityIsNetwork(realizer.capabilityId)

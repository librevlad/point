package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.Relation
import com.point.core.model.RelationType

import com.point.core.model.tr
/*
 * Сделанное остаётся у исходника (Product Constitution P4).
 *
 * «Действие над объектом возвращает результат к этому объекту: знание — в его факты, новый
 * объект — рядом, как найденное. Нарушение, если после действия человек оказывается не там,
 * где начал, без явной причины».
 *
 * Компьютер так и делал (решение N1-A): результат — chip у исходника. Телефон уводил человека
 * внутрь результата, и исходник, над которым он работал, пропадал с экрана: сделал PDF из
 * снимка — и снимка больше не видно, а чтобы сделать из него ещё и текст, надо искать
 * «назад». Здесь — общие правила обеих поверхностей: как сказать о сделанном и как узнать,
 * что из чего сделано.
 */

/** «Готово: отчёт.pdf» — что сделано, словами результата, а не механизма (P7). */
fun madeMessage(produced: PointObject): String {
    val name = produced.metadata["name"]?.trim()?.takeIf { it.isNotEmpty() }
    val noun = produced.metadata[META_YIELD_NOUN]?.substringBefore(" · ")?.trim()?.takeIf { it.isNotEmpty() }
    return tr("Готово: ") + (name ?: noun ?: madeNoun(produced.state.kind))
}

private fun madeNoun(kind: ObjectKind): String = when (kind) {
    ObjectKind.TEXT -> tr("текст")
    ObjectKind.IMAGE -> tr("изображение")
    ObjectKind.PDF -> "PDF"
    ObjectKind.OFFICE -> tr("документ")
    ObjectKind.URL -> tr("ссылка")
    ObjectKind.AUDIO -> tr("запись")
    ObjectKind.ZIP -> tr("архив")
    ObjectKind.COLLECTION -> tr("набор файлов")
    else -> tr("файл")
}

/** Что сделано из объекта [id]: найденные рядом объекты, полученные из него действием. */
fun madeOf(id: String, found: List<PointObject>, relations: List<Relation>): List<PointObject> =
    found.filter { made -> relations.any { it.type == RelationType.DERIVED_FROM && it.fromId == made.id && it.toId == id } }

/** Из чего сделан объект [id] — если исходник стоит рядом. */
fun madeFromOf(id: String, found: List<PointObject>, relations: List<Relation>): PointObject? =
    found.firstOrNull { source -> relations.any { it.type == RelationType.DERIVED_FROM && it.fromId == id && it.toId == source.id } }

package com.point.core.flow

const val COLLECTION_ITEMS_LIMIT = 500

const val COLLECTION_SCAN_LIMIT = 10_000

data class CollectionContent<T>(

    val shown: List<T>,

    val total: Int,

    val atLeast: Boolean = false,
) {

    val truncated: Boolean get() = atLeast || shown.size < total

    fun <R> map(transform: (T) -> R): CollectionContent<R> =
        CollectionContent(shown.map(transform), total, atLeast)

    companion object {
        fun <T> empty(): CollectionContent<T> = CollectionContent(emptyList(), 0)
    }
}

fun <T> collectionContent(
    entries: Sequence<T>,
    limit: Int = COLLECTION_ITEMS_LIMIT,
    scanLimit: Int = COLLECTION_SCAN_LIMIT,
    isFile: (T) -> Boolean,
    name: (T) -> String,
    order: List<String> = emptyList(),
): CollectionContent<T> {
    val files = ArrayList<T>()
    var scanned = 0
    var capped = false
    for (entry in entries) {
        if (scanned >= scanLimit) {
            capped = true
            break
        }
        scanned++
        if (isFile(entry)) files += entry
    }
    val ordered = inCollectionOrder(files, order, name)
    return CollectionContent(ordered.take(limit.coerceAtLeast(0)), ordered.size, capped)
}

/**
 * Порядок страниц набора — знание самого набора, а не имя файла (#1207).
 *
 * Несколько фото одной накладной приходят в Point в том порядке, в каком их снимали, а
 * имена им даёт камера. Человек переставляет страницы на экране набора — и это
 * Enrichment: набор остаётся тем же объектом, у него появляется знание «в каком порядке
 * читать». Его хранят метаданные объекта-коллекции — список имён страниц по порядку; его
 * читают и список на экране, и «Сканировать в PDF», и «В Excel». Имя файла остаётся
 * запасным порядком — для страниц, о которых знания нет.
 */
const val META_COLLECTION_ORDER = "collection.order"

fun collectionOrder(metadata: Map<String, String>): List<String> =
    metadata[META_COLLECTION_ORDER]?.split(ORDER_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

fun collectionOrderValue(names: List<String>): String =
    names.filter { it.isNotEmpty() }.joinToString(ORDER_SEPARATOR)

/**
 * Страницы в порядке набора: известные порядку — как велит порядок, остальные — за ними
 * по имени. Без знания о порядке — просто по имени, как и раньше.
 */
fun <T> inCollectionOrder(items: List<T>, order: List<String>, name: (T) -> String): List<T> {
    val rank = order.withIndex().associate { (i, n) -> n to i }
    val (known, rest) = items.partition { name(it) in rank }
    return known.sortedBy { rank.getValue(name(it)) } + rest.sortedBy { name(it).lowercase() }
}

// Имена файлов управляющих символов не несут (`safeFileName` заменяет их пробелом),
// поэтому перевод строки делит список однозначно.
private const val ORDER_SEPARATOR = "\n"

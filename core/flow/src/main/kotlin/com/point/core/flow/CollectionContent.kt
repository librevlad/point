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
    files.sortBy { name(it).lowercase() }
    return CollectionContent(files.take(limit.coerceAtLeast(0)), files.size, capped)
}

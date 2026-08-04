package com.point.core.flow

/**
 * Сколько элементов набора Point берёт в руки за один обход.
 *
 * Предел нужен не ради скорости первого экрана — содержимое грузится позже и в фоне, — а потому
 * что у обхода дерева нет естественного конца: архив на тысячу файлов честно даст тысячу объектов,
 * каждый со своим классификатором и своим `length()`, и всё это ради списка, из которого человек
 * откроет один.
 */
const val COLLECTION_ITEMS_LIMIT = 500

/**
 * Сколько записей дерева вообще обходим, прежде чем перестать считать.
 *
 * Второй предел — про сам обход, а не про показ: чтобы сказать «из 1340», надо дойти до конца
 * дерева, и на наборе из ста тысяч записей это уже цена. Дойдя до потолка, Point перестаёт
 * считать и **говорит об этом** ([CollectionContent.atLeast]), а не выдаёт неполное число за
 * полное. Заодно это страховка от дерева, которое не кончается (ссылка на саму себя).
 */
const val COLLECTION_SCAN_LIMIT = 10_000

/**
 * Содержимое набора: то, что Point показывает, и сколько там на самом деле.
 *
 * Обрезанный список без второго числа — молчаливая ложь: снаружи «Содержимое · 500» неотличимо
 * от набора ровно из пятисот файлов. Поэтому [total] едет рядом с [shown] всегда, а [atLeast]
 * говорит, что и само [total] — «не меньше чем», потому что обход упёрся в свой потолок.
 */
data class CollectionContent<T>(
    /** Показанная часть — не длиннее предела. */
    val shown: List<T>,
    /** Сколько файлов насчитано при обходе. */
    val total: Int,
    /** Обход упёрся в потолок: [total] — нижняя граница, а не точное число. */
    val atLeast: Boolean = false,
) {
    /** Показано не всё — экран обязан сказать это словами. */
    val truncated: Boolean get() = atLeast || shown.size < total

    /** То же содержимое, но элементы во что-то превращены (файл → объект). */
    fun <R> map(transform: (T) -> R): CollectionContent<R> =
        CollectionContent(shown.map(transform), total, atLeast)

    companion object {
        fun <T> empty(): CollectionContent<T> = CollectionContent(emptyList(), 0)
    }
}

/**
 * Обход набора с двумя пределами — чистый, поэтому проверяемый без файловой системы.
 *
 * [entries] — записи дерева в любом порядке (у реализации это `walkTopDown()`); [isFile] отделяет
 * файлы от каталогов, [name] даёт имя для сортировки. Материализуется только показанная часть:
 * порядок алфавитный, чтобы «первые 500» означало первые по тому же порядку, в каком человек
 * их видит, а не по случайному порядку обхода.
 */
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

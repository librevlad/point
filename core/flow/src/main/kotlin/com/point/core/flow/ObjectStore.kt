package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef

interface ObjectStore {

    /**
     * Как эта вещь называется у системы — до того, как её начали копировать (#640).
     *
     * Спрашивается имя, а не содержимое: человеку нужно видеть, что именно открывается,
     * ещё до первого прочитанного байта. Система имени не знает — `null`, и Point молчит,
     * а не выдумывает.
     */
    suspend fun nameOf(sourceUri: String): String? = null

    suspend fun ingest(sourceUri: String, mime: String): PointObject

    suspend fun ingestMultiple(sources: List<String>): PointObject

    /**
     * Объект, рождённый действием, приходит в Graph со своим происхождением (ADR-0001 §2, §8).
     *
     * [from] — объект, из которого он получен, [by] — действие, которое его сделало. Без них
     * результат ложился в Graph сиротой с происхождением «дано»: страница разложенного PDF,
     * скан и озвучка выглядели так же, как присланное человеком, и по графу нельзя было
     * сказать, откуда они взялись (#1127, #1132).
     */
    suspend fun put(
        result: ResultObject,
        from: PointObject? = null,
        by: com.point.core.model.CapabilityId? = null,
    ): PointObject

    suspend fun children(
        collection: PointObject,
        limit: Int = COLLECTION_ITEMS_LIMIT,
    ): CollectionContent<PointObject>

    suspend fun readText(obj: PointObject, limit: Int): String

    /**
     * Указывает ли адрес источника внутрь этой же рабочей папки (#1419).
     *
     * Так приезжает собственный файл Point: «Открыть» без стороннего приложения запускало сам
     * Point, а приём начинался с очистки папки — и стирал то, что собирался прочитать. Своё
     * перед приёмом не стирается. Хранилище, не раздающее адресов наружу, отвечает «нет».
     */
    fun isOwn(sourceUri: String): Boolean = false

    /**
     * Место под новый файл в рабочей папке: **только путь, файла по нему ещё нет**.
     *
     * Кто просит место, тот и пишет по нему сам — файлом (`writeText`, `writeBytes`) или
     * папкой набора (`mkdirs()`, так делают «Слайды» и «Снять ещё»). Хранилище, которое
     * создаёт по этому пути пустой файл заранее, ломает второе молча: `mkdirs()` о файл
     * возвращает `false`, и общий код узнаёт об этом только на одном из устройств (#1412 —
     * «Слайды» на компьютере). Расширение — без точки или с ней, одинаково.
     */
    suspend fun newScratchFile(extension: String): ScratchRef

    suspend fun clear()

    /**
     * Убрать копии, которые пережили смерть процесса и уже никому не нужны (#1012).
     *
     * Копия объекта временная, но не мгновенная: Point намеренно возвращает человека к его
     * последнему объекту после смерти процесса, и чистить всё на старте значило бы оставить
     * граф без байтов. Поэтому срок: копия живёт до следующего запуска, но не дольше
     * названного здесь возраста.
     *
     * [before] — момент времени, старше которого копия считается брошенной. Хранилищу,
     * которое ничего не кладёт на диск, забывать нечего — отсюда пустая реализация по
     * умолчанию.
     */
    suspend fun forgetOlderThan(before: Long) = Unit
}

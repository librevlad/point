package com.point.core.flow

import com.point.core.model.HistoryEntry
import com.point.core.model.PointObject

interface HistoryStore {

    suspend fun record(obj: PointObject)

    suspend fun update(obj: PointObject)

    suspend fun recent(limit: Int = 30): List<HistoryEntry>

    suspend fun open(entryId: String): PointObject?

    /**
     * Убрать одну запись со всем, что она оставила (#543): и сам файл, и копии улик рядом с ним.
     * Половинчатое удаление — дефект: человек убирал не строку списка, а распознанный текст.
     */
    suspend fun remove(entryId: String)

    suspend fun clearAll()

    /**
     * Сколько объектов Point помнит и сколько места это занимает (#821).
     *
     * Человек не видел ни числа, ни занятого места: копии файлов лежат в каталоге Point, а
     * сказано о них нигде не было.
     */
    suspend fun footprint(): HistoryFootprint = HistoryFootprint(0, 0L)
}

/** Что Point помнит: сколько записей и сколько байт они занимают (#821). */
data class HistoryFootprint(val count: Int, val bytes: Long) {

    /** Предел памяти: дальше самое старое забывается само. */
    companion object { const val KEPT = 50 }
}

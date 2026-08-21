package com.point.core.flow

import com.point.core.model.PointObject

interface ExternalEye {

    fun available(): Boolean

    suspend fun read(obj: PointObject): ExternalReading
}

data class ExternalReading(

    /** Пусто — читатели посмотрели и текста не увидели: ответ «не нашлось», а не срыв (#1054). */
    val text: String,

    val reader: String,

    val where: String,

    val promise: String = "",
)

package com.point.core.flow

import com.point.core.model.PointObject

interface ExternalEye {

    fun available(): Boolean

    suspend fun read(obj: PointObject): ExternalReading
}

data class ExternalReading(
    val text: String,

    val reader: String,

    val where: String,

    val promise: String = "",
)

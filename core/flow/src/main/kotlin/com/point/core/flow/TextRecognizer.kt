package com.point.core.flow

import com.point.core.model.PointObject

interface TextRecognizer {
    suspend fun recognize(obj: PointObject): String
}

interface AtomRecognizer : TextRecognizer {

    suspend fun read(obj: PointObject): AtomLayer

    override suspend fun recognize(obj: PointObject): String = read(obj).text
}

package com.point.data

import com.point.core.flow.OfficeTextExtractor
import com.point.core.model.PointObject
import javax.inject.Inject

class OoxmlOfficeTextExtractor @Inject constructor() : OfficeTextExtractor {
    private val delegate = com.point.core.flow.OoxmlOfficeTextExtractor()
    override suspend fun extractText(obj: PointObject): String = delegate.extractText(obj)
    override suspend fun slides(obj: PointObject): List<String> = delegate.slides(obj)
}

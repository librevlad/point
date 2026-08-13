package com.point.executors

import com.point.core.flow.PdfRasterizer
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef

/**
 * Страница снимком: заглушка для проверок, которые про текстовый слой, а не про снимки (#933).
 */
object NoPages : PdfRasterizer {
    override suspend fun rasterize(obj: PointObject) = ScratchRef("")
    override suspend fun rasterizeFirstPage(obj: PointObject): ScratchRef? = null
}

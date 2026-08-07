package com.point.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.point.core.flow.ObjectStore
import com.point.core.flow.PdfRasterizer
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class PdfRendererRasterizer @Inject constructor(
    private val store: ObjectStore,
) : PdfRasterizer {

    override suspend fun rasterize(obj: PointObject): ScratchRef = withContext(Dispatchers.IO) {
        val dir = File(store.newScratchFile("pages").value).apply { mkdirs() }
        val src = File(obj.uri.value)

        ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer = PdfRenderer(pfd)
            try {

                val pages = minOf(renderer.pageCount, MAX_PAGES)
                if (renderer.pageCount > MAX_PAGES) {
                    Log.w(TAG, "PDF has ${renderer.pageCount} pages; rasterising the first $MAX_PAGES")
                }
                for (i in 0 until pages) {
                    val page = renderer.openPage(i)
                    try {
                        val w = (page.width * SCALE).coerceIn(1, MAX_DIM)
                        val h = (page.height * SCALE).coerceIn(1, MAX_DIM)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        File(dir, "page-%03d.jpg".format(i + 1)).outputStream().use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                        bmp.recycle()
                    } finally {
                        page.close()
                    }
                }
            } finally {
                renderer.close()
            }
        }
        ScratchRef(dir.absolutePath)
    }

    override suspend fun rasterizeFirstPage(obj: PointObject): ScratchRef? = withContext(Dispatchers.IO) {
        runCatching {
            ParcelFileDescriptor.open(File(obj.uri.value), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val renderer = PdfRenderer(pfd)
                try {
                    if (renderer.pageCount == 0) return@use null
                    val page = renderer.openPage(0)
                    try {
                        val w = (page.width * SCALE).coerceIn(1, MAX_DIM)
                        val h = (page.height * SCALE).coerceIn(1, MAX_DIM)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val ref = store.newScratchFile("jpg")
                        File(ref.value).outputStream().use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                        bmp.recycle()
                        ref
                    } finally {
                        page.close()
                    }
                } finally {
                    renderer.close()
                }
            }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "PointPdf"
        const val SCALE = 2
        const val MAX_DIM = 2400
        const val MAX_PAGES = 100
    }
}

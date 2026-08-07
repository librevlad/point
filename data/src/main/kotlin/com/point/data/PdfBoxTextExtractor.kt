package com.point.data

import android.content.Context
import com.point.core.flow.PdfTextExtractor
import com.point.core.model.PointObject
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class PdfBoxTextExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) : PdfTextExtractor {

    override suspend fun extractText(obj: PointObject): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        PDDocument.load(File(obj.uri.value)).use { document ->
            PDFTextStripper().getText(document).trim()
        }
    }

    private fun ensureInitialized() {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
    }

    private companion object {
        @Volatile
        var initialized = false
    }
}

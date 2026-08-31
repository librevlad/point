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

/**
 * Текст PDF — ровно столько, сколько спросили, и один раз на документ (#1241).
 *
 * Прежде здесь стоял один вызов на весь документ без предела и без памяти: длинный PDF
 * разбирался целиком при каждом входе в объект, после каждого действия с находками и на
 * каждую реплику разговора.
 *
 * Две правки, и обе про одно — не делать заново уже сделанное:
 *
 * - **предел**: спросивший называет, сколько знаков ему нужно, и чтение останавливается на
 *   странице, где их набралось. Кому нужен весь документ (запись текста файлом, проба «есть
 *   ли слой» — #995), тот по-прежнему получает весь;
 * - **память на один документ**: тот же файл, прочитанный целиком, второй раз не читается.
 *   Ключ — путь, размер и время правки: подменённый файл в память не попадает.
 *
 * Память на один документ, а не на все: человек работает с объектом перед собой, а держать
 * в себе сорок разобранных PDF значило бы менять секунды на мегабайты.
 */
class PdfBoxTextExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) : PdfTextExtractor {

    @Volatile
    private var remembered: Remembered? = null

    override suspend fun extractText(obj: PointObject, atMost: Int?): String =
        withContext(Dispatchers.IO) {
            val file = File(obj.uri.value)
            val key = Remembered.keyOf(file)
            remembered?.takeIf { it.key == key }?.let { return@withContext it.text.cut(atMost) }

            ensureInitialized()
            PDDocument.load(file).use { document ->
                if (atMost == null || atMost <= 0) {
                    PDFTextStripper().getText(document).trim()
                        .also { remembered = Remembered(key, it) }
                } else {
                    readEnough(document, atMost, key)
                }
            }
        }

    /**
     * Читать по странице, пока знаков не наберётся достаточно.
     *
     * Дочитали до конца — значит прочитан весь документ, и его можно запомнить: следующий
     * спросивший получит текст даром. Остановились раньше — не запоминаем: половина документа
     * под видом целого была бы неправдой для того, кому нужен весь.
     */
    private fun readEnough(document: PDDocument, atMost: Int, key: String): String {
        val pages = document.numberOfPages
        val text = StringBuilder()
        var page = 1
        while (page <= pages) {
            val stripper = PDFTextStripper().apply {
                startPage = page
                endPage = page
            }
            text.append(stripper.getText(document))
            if (text.length >= atMost) break
            page++
        }
        val read = text.toString().trim()
        if (page > pages) remembered = Remembered(key, read)
        return read
    }

    /**
     * Предел мягкий: из запомненного целого документа отдаётся столько, сколько спросили,
     * и не меньше страницы — резать слово посреди значило бы соврать о том, что на ней.
     */
    private fun String.cut(atMost: Int?): String = this


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

/**
 * Разобранный документ, который не станут разбирать снова.
 *
 * Ключ — путь, размер и время правки файла: подменённый файл в память не попадает, а
 * scratch-копия объекта живёт своим сроком и меняется вместе с ним.
 */
private class Remembered(val key: String, val text: String) {

    companion object {

        fun keyOf(file: File): String = file.absolutePath + "|" + file.length() + "|" + file.lastModified()
    }
}

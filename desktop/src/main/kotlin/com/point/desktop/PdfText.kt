package com.point.desktop

import com.point.core.flow.READER_NOT_DECODED
import com.point.core.flow.readerFailureIsFatal
import org.apache.pdfbox.io.RandomAccessBufferedFileInputStream
import org.apache.pdfbox.io.RandomAccessRead
import java.io.File

/**
 * Текст из PDF на компьютере (#631). Телефон это давно умеет тем же PDFBox — расхождение
 * поверхностей читалось как «это разные программы». Норму «ПК и телефон — один продукт»
 * сторожит `checks/src/test/kotlin/com/point/checks/DesktopKeepsPhoneLookTest.kt`; документа,
 * на который вела прежняя ссылка, в репозитории не было (#1234).
 */
fun interface PdfText {

    /** `null` — прочитать не вышло; пустая строка — текстового слоя нет (скан). */
    fun of(file: File): String?

    /**
     * Почему файл не открылся как PDF — техническим сигналом ридера ([READER_NOT_DECODED]),
     * или `null`: файл открывается, или беда не в нём самом (не прочитались байты, защита,
     * нехватка памяти).
     *
     * Отдельный вопрос, а не второй смысл `null` у [of]: «прочитать не вышло» там — и пароль,
     * и сорвавшееся извлечение, и обломок вместо документа. Негодным объявляется только
     * последний: сорвавшаяся попытка знанием не является (Конституция §13), и объект, о котором
     * ничего не узнали, дверей чтения не теряет.
     *
     * По умолчанию — молчание: кто не умеет отличить поломку файла от неудачной попытки, тот
     * никого негодным и не объявляет.
     */
    fun trouble(file: File): String? = null
}

class PdfBoxText internal constructor(

    /**
     * Откуда разбор берёт байты файла. Шов ради одного вопроса: сорвалось ли чтение — а это
     * бывает и посреди разбора (сетевая папка, файл OneDrive без сети), и проверяется только
     * подменой источника.
     */
    private val source: (File) -> RandomAccessRead,
) : PdfText {

    constructor() : this(::RandomAccessBufferedFileInputStream)

    override fun of(file: File): String? = runCatching {
        org.apache.pdfbox.pdmodel.PDDocument.load(file).use { document ->
            org.apache.pdfbox.text.PDFTextStripper().getText(document).trim()
        }
    }.getOrNull()

    /**
     * Сигнал называет тот, кто видел неразобранные байты (#1258): разбор pdfbox, бросивший
     * `IOException` над прочитанными байтами, — это и есть «байты не разобрались в документ».
     *
     * Всё остальное — про попытку, а не про файл (Конституция §13):
     * - не прочитались сами байты — файл заперт другой программой, нет прав, файл OneDrive без
     *   сети, отвалилась сетевая папка. `IOException` у pdfbox тот же, что у обломка, поэтому
     *   источник байтов отвечает сам за себя ([Watched]): сорвался он хоть раз — приговора нет;
     * - документ объявляет защиту (`/Encrypt`) — он цел, его нельзя открыть без ключа. Пароль
     *   pdfbox называет своим исключением, а защиту сертификатом и незнакомую защиту — тем же
     *   голым `IOException`; спрашивается поэтому сам файл, а не вид исключения. Телефон такой
     *   документ тоже не зовёт повреждённым: его ридер отвечает отказом доступа;
     * - пропавший файл и нехватка памяти.
     */
    override fun trouble(file: File): String? {
        if (!file.isFile) return null
        val opened = try {
            Watched(source(file))
        } catch (attempt: Throwable) {
            return null
        }
        return opened.use { bytes ->
            try {
                if (declaresProtection(bytes)) return@use null
                bytes.seek(0)
                val parser = org.apache.pdfbox.pdfparser.PDFParser(bytes)
                parser.parse()
                runCatching { parser.pdDocument.close() }
                null
            } catch (locked: org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                null
            } catch (unparsed: java.io.IOException) {
                if (bytes.failed) null else READER_NOT_DECODED
            } catch (attempt: Throwable) {
                null
            }
        }
    }
}

/**
 * Источник байтов, который помнит, что подвёл (#1258, Конституция §13): «не прочиталось» —
 * про попытку, и приговором файлу стать не может.
 *
 * Конец файла сбоем источника не считается: это свойство самого файла, и оборванный
 * документ по нему и узнаётся.
 */
private class Watched(private val inner: RandomAccessRead) : RandomAccessRead by inner {

    var failed = false
        private set

    private inline fun <T> watched(step: () -> T): T = try {
        step()
    } catch (end: java.io.EOFException) {
        throw end
    } catch (read: java.io.IOException) {
        failed = true
        throw read
    }

    override fun read(): Int = watched { inner.read() }
    override fun read(b: ByteArray): Int = watched { inner.read(b) }
    override fun read(b: ByteArray, offset: Int, length: Int): Int = watched { inner.read(b, offset, length) }
    override fun getPosition(): Long = watched { inner.position }
    override fun seek(position: Long) = watched { inner.seek(position) }
    override fun length(): Long = watched { inner.length() }
    override fun peek(): Int = watched { inner.peek() }
    override fun rewind(bytes: Int) = watched { inner.rewind(bytes) }
    override fun readFully(length: Int): ByteArray = watched { inner.readFully(length) }
    override fun isEOF(): Boolean = watched { inner.isEOF }
    override fun available(): Int = watched { inner.available() }
}

private val PROTECTION_KEY = "/Encrypt".toByteArray(Charsets.US_ASCII)

/**
 * Объявляет ли документ защиту: ключ `/Encrypt` стоит в словаре трейлера, а тот никогда не
 * сжимается — и в классической таблице, и в потоке перекрёстных ссылок он лежит открытым
 * текстом. Читается весь файл: трейлер бывает и в начале (линеаризованный документ), и в
 * конце. Встретился ключ где-то ещё — документ поломкой не назовут, и это безопасная сторона:
 * лишняя дверь откажет по нажатию своими словами, а ложное «повреждён» снять нечем.
 */
private fun declaresProtection(bytes: RandomAccessRead): Boolean {
    val buffer = ByteArray(64 * 1024)
    var carried = 0
    while (true) {
        val read = bytes.read(buffer, carried, buffer.size - carried)
        if (read <= 0) return false
        val end = carried + read
        if (buffer.holds(PROTECTION_KEY, end)) return true

        // Ключ на стыке двух кусков: хвост прошлого куска идёт в начало следующего.
        carried = minOf(PROTECTION_KEY.size - 1, end)
        System.arraycopy(buffer, end - carried, buffer, 0, carried)
    }
}

private fun ByteArray.holds(token: ByteArray, size: Int): Boolean {
    var at = 0
    while (at + token.size <= size) {
        var same = 0
        while (same < token.size && this[at + same] == token[same]) same++
        if (same == token.size) return true
        at++
    }
    return false
}

/**
 * Скан — PDF, из которого текст не достаётся файлом. Спрашивается при приёме, а не
 * исследованием: своего цикла обогащения у компьютера нет, а знать это нужно до того, как
 * человек увидит двери (иначе «Извлечь текст» на скане заканчивается пустотой вместо честного
 * отсутствия двери).
 *
 * Нечитаемый слой считается тем же самым (#933, #995): у документа своя раскладка шрифта,
 * кириллица лежит под латинскими кодами, и «извлечённый» текст — мусор. Такой документ читают
 * страницами, а не файлом, и дверь ему нужна та же, что скану.
 *
 * Само правило живёт в `:core:flow` и одно на обе поверхности: телефон спрашивает то же самое
 * исследованием `pdf-image-shape`. Иначе один документ здесь «скан», а там нет.
 *
 * Спрашивается весь документ, а не первые страницы (#995). Проба в три страницы судила не тот
 * документ, что исполнитель: у счёта с читаемой первой страницей и подменённой раскладкой
 * дальше приём отвечал «не скан», рисовалась быстрая дверь, а «Извлечь текст» по нажатию
 * отказывало и звало «Прочитать документ» — дверь, которой рядом не было. Телефон здесь тоже
 * читает слой целиком: одно правило значит и одну улику.
 *
 * Судится уже прочитанный слой ([PdfText.of]), а не файл: приём читает документ один раз, и
 * то же чтение отвечает, не обломок ли это вместо PDF ([brokenPdf]). Не прочитанный слой
 * (`null`) сканом не считается — «не прочиталось» не равно «текста нет».
 */
fun looksScanned(layer: String?): Boolean = com.point.core.flow.pdfLayerUnusable(layer)

/**
 * PDF, который не открывается как PDF вовсе, — негоден, и это знание о самом файле.
 *
 * Прежде компьютер такой файл не узнавал: обломок давал `null` вместо слоя, а `null` — не
 * скан, и человеку предлагались все двери PDF подряд. Каждая потом отказывала своими словами,
 * и все мимо: «Извлечь текст» винило компьютер («Компьютер не смог открыть этот PDF»), «В Word»
 * советовало распознать скан. Телефон тот же файл называет повреждённым и двери чтения ему не
 * предлагает (`notePreviewFailure` → `offeredWhenUnfit`).
 *
 * Правило здесь то же самое, а не похожее: приговор выносит [readerFailureIsFatal], слова
 * произносит `readerFailure` — тот же словарь, что у телефона. Своего разбора поломки у
 * компьютера нет. Спрашивается только у PDF, чей слой [PdfText.of] не достал: у здорового
 * документа второго чтения не бывает.
 *
 * Ответ — технический сигнал, а не слова: метку кладёт приём, и слова ей даёт `readerFailure`
 * в момент записи, как требует `UnfitReasonIsOurOwnWordsTest`.
 */
fun brokenPdf(pdf: PdfText, file: File): String? =
    pdf.trouble(file)?.takeIf(::readerFailureIsFatal)

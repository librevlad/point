package com.point.executors

import android.graphics.Bitmap
import com.point.core.flow.META_WHOLE_FRAME
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerMeta
import com.point.core.flow.reportStage
import com.point.core.flow.wholeFrameNote
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Готовый снимок и то, нашли ли на нём страницу (#1333).
 *
 * Второе едет вместе с первым, потому что по картинке этого уже не видно: выбеленный кадр
 * целиком выглядит как выбеленная страница, и без пометки «не нашлось» снаружи неотличимо
 * от успеха — ровно тот дефект, ради которого написана карточка.
 */
internal class ScannedPage(val ref: ScratchRef, val wholeFrame: Boolean)

/**
 * Обработка снимка в страницу (#1333): путь к снимку внутрь, готовая страница наружу.
 *
 * За интерфейсом — нативная OpenCV, декодирование и запись файла: side effects, которые в
 * JVM не поднимаются. Решение «что человек получит и как это помечено» живёт снаружи, в
 * исполнителе, и проверяется напрямую.
 */
internal interface PageScan {

    /** Готовый снимок или `null` — обработать не вышло совсем. */
    suspend fun scan(imagePath: String): ScannedPage?
}

/**
 * Своё зрение (#1333): сетка строк, а не вышло — четыре угла листа, а не нашлось — кадр целиком.
 *
 * Снимок читается один раз: не нашедшая страницу попытка отдаёт тот же уже раскодированный
 * кадр обработке «как есть», и человек слышит «Читаю снимок» тоже один раз.
 *
 * Вторая пара глаз — обученная модель углов — здесь не живёт: она приедет вместе со своей
 * реализацией и своей очередью исполнителей (#1044), а не раньше неё.
 */
internal class OpenCvPageScan(private val store: ObjectStore) : PageScan {

    override suspend fun scan(imagePath: String): ScannedPage? {
        reportStage("Читаю снимок")
        val src = Bitmaps.decodeUpright(imagePath, Bitmaps.SCAN_PLUS_MAX_PX)
            ?: error("Не удалось прочитать изображение")
        try {
            OpenCvScan.enhance(src)?.let { return saved(it, wholeFrame = false) }

            // Страницы не нашли. Кадр всё равно стоит выбелить: снимок уже обрезанного или
            // снятого в упор листа своё зрение страницей не считает, и отказ отнял бы у
            // человека работу, которую он получает сегодня. Но за выпрямленную страницу
            // результат не выдаётся — пометку кладёт исполнитель.
            reportStage("Страницу целиком не нашёл — обрабатываю снимок как есть")
            return OpenCvScan.enhanceAsIs(src)?.let { saved(it, wholeFrame = true) }
        } finally {
            src.recycle()
        }
    }

    private suspend fun saved(page: Bitmap, wholeFrame: Boolean): ScannedPage {
        reportStage("Сохраняю")
        val ref = store.newScratchFile("jpg")
        File(ref.value).outputStream().use { page.compress(Bitmap.CompressFormat.JPEG, Bitmaps.JPEG_QUALITY, it) }
        page.recycle()
        return ScannedPage(ref, wholeFrame)
    }
}

/**
 * Скан страницы (#1333): «Скан» и «Скан с цветом» отличались только именем способности и
 * пометкой `op`, а делали одно и то же — теперь это один исполнитель.
 *
 * Главное здесь — пометка результата. Кадр, на котором страницы не нашли, помечается иначе,
 * чем выпрямленная страница: прогресс гаснет, а пометка остаётся на объекте, и опереться на
 * неё могут оба — человек глазами и следующий шаг (#1041).
 */
class PageScanRealizer internal constructor(
    override val capabilityId: CapabilityId,
    private val op: String,
    private val eyes: PageScan,
) : Realizer {

    constructor(capabilityId: CapabilityId, op: String, store: ObjectStore) :
        this(capabilityId, op, OpenCvPageScan(store))

    override val meta = RealizerMeta(priority = 20)

    override fun isAvailable(): Boolean = OpenCvScan.available

    override fun unavailableReason(): String = "нужен пакет обработки снимков"

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val done = eyes.scan(input.uri.value)
                    ?: return@runCatching ActionResult.Failure(SCAN_FAILED, recoverable = true)
                ActionResult.Success(
                    ResultObject(ObjectKind.IMAGE, "image/jpeg", done.ref, marks(done)),
                )
            }.getOrElse { ActionResult.Failure(it.message ?: SCAN_FAILED, recoverable = true) }
        }

    /** Одна пометка на оба пути скана: и на снимок поодиночке, и на набор снимков в PDF. */
    private fun marks(done: ScannedPage): Map<String, String> =
        mapOf("op" to op) + listOfNotNull(
            wholeFrameNote(wholeFrames = if (done.wholeFrame) 1 else 0, pages = 1)
                ?.let { META_WHOLE_FRAME to it },
        )

    internal companion object {

        const val SCAN_FAILED = "Страницу на снимке не удалось выпрямить — снимите её целиком и при ровном свете"
    }
}

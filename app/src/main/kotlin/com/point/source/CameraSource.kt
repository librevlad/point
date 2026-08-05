package com.point.source

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import javax.inject.Inject

/**
 * Камера как источник объекта (#246) — чужими руками.
 *
 * Снимает системное приложение камеры, поэтому разрешение `CAMERA` Point не просит вовсе: в его
 * манифесте нет ни одного `uses-permission`, и терять это свойство ради двух сэкономленных тапов
 * не стоит.
 *
 * Кадр пишется в кэш, а НЕ в scratch, и это не мелочь: scratch — рабочая копия текущей работы, и
 * она стирается по её окончании (`ObjectStore.clear`). Кадр, снятый до начала работы, там не
 * доживал до приёма — первая живая проверка дала ровно это: «Не удалось открыть объект», потому
 * что файл к тому моменту уже стёрли. В scratch снимок попадёт обычным путём — при приёме
 * объекта, как любой входящий файл (инвариант «объект копируется в scratch при приёме»).
 */
class CameraSource @Inject constructor() : ObjectSource {

    override val id = "camera"
    override val label = "Камера"
    override val icon = "camera"

    /**
     * Куда пишется кадр. Обычным полем этому не жить: пока снимает камера, экран выбора стоит
     * позади неё и первым идёт под нож при нехватке памяти — поэтому путь уезжает в `Bundle`
     * ([saveState]) и возвращается оттуда ([restoreState]). До #454 он просто исчезал, и снятая
     * фотография не становилась объектом молча.
     */
    private var target: File? = null

    override fun saveState(): String? = target?.absolutePath

    override fun restoreState(state: String?) {
        target = state?.takeIf { it.isNotBlank() }?.let(::File)
    }

    override fun isAvailable(context: Context): Boolean =
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) != null

    override suspend fun request(context: Context): Intent {
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
        target = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    override suspend fun read(context: Context, data: Intent?): Produced? {
        val file = target ?: return null
        target = null
        // Отмена съёмки оставляет заготовленный файл нулевым — объектом он не становится, и
        // говорить об этом человеку нечего: он сам только что нажал «отмена».
        //
        // Имя кадру даёт время самого файла (#533): камера дописала его в момент съёмки, а сюда мы
        // возвращаемся позже — иногда сильно позже, если Point выгружали из памяти. «Снимок,
        // 4 авг 19:25» должен говорить, когда СНЯЛИ, а не когда объект дошёл.
        val takenAt = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        return captureToProduced(android.net.Uri.fromFile(file).toString(), file.length(), takenAt)
    }
}

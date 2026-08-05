package com.point.executors

import android.graphics.Bitmap
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** image -> JPEG-compressed image. */
class ImageCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "compress"

    /**
     * Не [Latency.INSTANT] (#288) — та же правка и по той же причине, что у «Скана».
     *
     * «Сжать» декодирует снимок целиком в память и кодирует его обратно: на кадре с камеры это
     * секунды, а не мгновение. Мгновенным объявлено оно было молча, по умолчанию — и вместе с
     * объявлением получало мгновенный тир пузырька, то есть обещало человеку то, чего не делает.
     */
    override val meta = CapabilityMeta(latency = Latency.FAST)

    override fun label(state: ObjectState) = "Сжать"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)

    companion object { val ID = CapabilityId("image") }
}

/**
 * Две стадии, потому что работы правда две (#288).
 *
 * Снимок с камеры сначала разворачивается в память целиком (пятьдесят мегапикселей — это секунды
 * и сотня мегабайт), и только потом кодируется обратно в JPEG. Это не один непрерывный шаг,
 * который пришлось бы называть «Обрабатываю…», а два разных: человек, ждущий над объектом, видит,
 * что чтение уже позади.
 *
 * Первое слово сказано ДО первого касания пикселей — тем же приёмом, что в «Скане» и по той же
 * причине: `android.graphics` на JVM заглушка, и только эта стадия проверяема без телефона.
 */
class ImageRealizer @Inject constructor(
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = ImageCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage("Читаю изображение")
                val bitmap = Bitmaps.decodeUpright(input.uri.value)
                    ?: error("Не удалось прочитать изображение")
                reportStage("Сжимаю снимок")
                val ref = store.newScratchFile("jpg")
                File(ref.value).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }
                bitmap.recycle()
                ActionResult.Success(
                    ResultObject(ObjectKind.IMAGE, "image/jpeg", ref, mapOf("op" to "compress")),
                )
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка сжатия", recoverable = true) }
        }
}

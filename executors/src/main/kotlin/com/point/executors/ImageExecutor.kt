package com.point.executors

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.point.core.flow.Executor
import com.point.core.flow.ObjectStore
import com.point.core.model.ExecutorId
import com.point.core.model.ExecutorResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** image -> JPEG-compressed image. */
class ImageExecutor @Inject constructor(
    private val store: ObjectStore,
) : Executor {
    override val id = ExecutorId("image")
    override val icon = "compress"
    override fun title(state: ObjectState) = "Сжать"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)

    override suspend fun execute(input: PointObject, amendment: String?): ExecutorResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = BitmapFactory.decodeFile(input.uri.value)
                    ?: error("Не удалось прочитать изображение")
                val ref = store.newScratchFile("jpg")
                File(ref.value).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }
                bitmap.recycle()
                ExecutorResult.Success(
                    ResultObject(ObjectKind.IMAGE, "image/jpeg", ref, mapOf("op" to "compress")),
                )
            }.getOrElse { ExecutorResult.Failure(it.message ?: "Ошибка сжатия", recoverable = true) }
        }
}

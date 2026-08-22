package com.point.executors

import com.point.core.flow.AppLauncher
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.isFileBacked
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class OpenInCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "open-in"

    // Список приложений — ещё экран Point, но кончается шаг запуском выбранного, то есть
    // чужим экраном (#1131): подпись «Открываю в …» гаснет возвратом человека.
    override val meta = CapabilityMeta(priority = 60, localOnly = true, handsOff = true)

    override fun label(state: ObjectState) = "Открыть другим приложением"
    override fun accepts(state: ObjectState) =
        state.kind.isFileBacked && state.kind != ObjectKind.URL
    override fun produces(state: ObjectState) = state
    override fun intents(state: ObjectState) = setOf(Intent.OPEN)

    companion object { val ID = CapabilityId("open-in") }
}

class OpenInRealizer @Inject constructor(
    private val launcher: AppLauncher,
) : Realizer {
    override val capabilityId = OpenInCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = launcher.handlers(input).firstOrNull()
                    ?: error("Нет приложения для этого объекта")
                launcher.launch(target, input)
                ActionResult.Done("Открываю в ${target.label}")
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось открыть", recoverable = true) }
        }
}

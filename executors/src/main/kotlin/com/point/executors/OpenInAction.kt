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
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * "Открыть в…" — the device-actions differentiator (#66): show the actual installed apps that can
 * open this object and launch the chosen one. Interactive selection is handled by the ViewModel's
 * inline app-picker (it intercepts this capability's tap, like the cloud-consent gate); this realizer
 * is the non-interactive fallback (e.g. favorite-chain replay) — it opens the first available app.
 */
class OpenInCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "open-in"
    override val meta = CapabilityMeta(priority = 60)
    override fun label(state: ObjectState) = "Открыть в…"
    override fun accepts(state: ObjectState) =
        state.kind != ObjectKind.URL && state.kind != ObjectKind.COLLECTION
    override fun produces(state: ObjectState) = state // terminal
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

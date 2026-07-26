package com.point.executors

import com.point.core.flow.AppLauncher
import com.point.core.flow.AppTarget
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.ChosenApp
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #66 slice 4 — a remembered app pick as a FIRST-CLASS capability: «Telegram» is a
 * bubble in the same derived graph as built-in actions, ranked by the same policy and
 * learning from the same usage signal. Pairs are synthesised at process start from
 * [com.point.core.flow.ChosenApps] (a fresh pick joins the graph on the next launch).
 */
class AppCapability(private val app: ChosenApp) : Capability {
    override val id = idFor(app)
    override val icon = "app:${app.packageName}" // the UI resolves the real app icon
    override val meta = CapabilityMeta(priority = 70, latency = Latency.INSTANT)
    override fun label(state: ObjectState) = app.label
    override fun accepts(state: ObjectState) = state.kind == app.kind
    override fun produces(state: ObjectState) = state // terminal — the flow leaves for the app
    override fun intents(state: ObjectState) = setOf(Intent.OPEN)

    companion object {
        fun idFor(app: ChosenApp) = CapabilityId("app:${app.packageName}#${app.kind.name}")
    }
}

class AppOpenRealizer(
    private val app: ChosenApp,
    private val launcher: AppLauncher,
) : Realizer {
    override val capabilityId = AppCapability.idFor(app)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                launcher.launch(AppTarget(app.label, app.packageName, app.activity), input)
                ActionResult.Done("Открываю в ${app.label}")
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось открыть", recoverable = true) }
        }
}

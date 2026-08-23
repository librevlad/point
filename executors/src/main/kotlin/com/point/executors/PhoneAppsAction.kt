package com.point.executors

import com.point.core.flow.AppLauncher
import com.point.core.flow.AppTarget
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.Latency
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * У номера — те приложения, что стоят у человека (#466).
 *
 * Владелец: «мне часто надо взять номер и пробить, кто это», а звонит он обычно из мессенджера.
 * Раньше у номера было ровно два жёстко заданных действия — «Позвонить» и «Сообщение», — и оба
 * стояли высоко, хотя ими не пользуются.
 *
 * Список даёт система: приложения появляются потому, что **стоят**, а не потому, что мы
 * написали их имена. Ни одного имени стороннего сервиса в коде Point нет — сегодня это один
 * сервис, завтра другой, и вести чужой список Point не будет.
 */
class PhoneAppsCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "call"
    override val meta = CapabilityMeta(priority = 12, latency = Latency.INSTANT, handsOff = true)
    override fun label(state: ObjectState) = "Открыть номер в приложении"
    override fun accepts(state: ObjectState) = state.has(Feature.HAS_PHONE)
    override fun produces(state: ObjectState) = state
    override fun intents(state: ObjectState) = setOf(Intent.OPEN)

    companion object { val ID = CapabilityId("phone-apps") }
}

class PhoneAppsRealizer @Inject constructor(
    private val extractor: EntityExtractor,
    private val launcher: AppLauncher,
) : Realizer {

    override val capabilityId = PhoneAppsCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            val phone = phoneOf(input)
                ?: return@withContext ActionResult.Failure("Номер не найден", recoverable = false)
            val apps = runCatching { launcher.handlersForPhone(phone) }.getOrDefault(emptyList())
            if (apps.isEmpty()) {
                // Пустого списка человек не увидит: молчание тут хуже слов.
                return@withContext ActionResult.Failure(
                    "На этом телефоне нет приложения, которое умеет номера",
                    recoverable = false,
                )
            }
            val chosen = chosenApp(apps, amendment)
                ?: return@withContext ActionResult.NeedsInput(
                    prompt = "Чем открыть $phone?",
                    suggestions = apps.map { it.label },
                )
            runCatching { launcher.launchWithPhone(chosen, phone) }
                .fold(
                    onSuccess = { ActionResult.Done("Открыл ${chosen.label}: $phone") },
                    onFailure = { ActionResult.Failure("Не удалось открыть ${chosen.label}", recoverable = true) },
                )
        }

    private fun chosenApp(apps: List<AppTarget>, amendment: String?): AppTarget? = when {
        amendment.isNullOrBlank() -> apps.singleOrNull()
        else -> apps.firstOrNull { it.label.equals(amendment.trim(), ignoreCase = true) }
    }

    private suspend fun phoneOf(input: PointObject): String? =
        input.metadata[META_ENTITY_PREFIX + "phone"]?.takeIf { it.isNotBlank() }
            ?: firstEntity(extractor, input, EntityType.PHONE)
}

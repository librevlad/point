package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.ContactInserter
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * «Сохранить контакт» (#464): телефон (и почта), найденные на странице, → системный экран нового
 * контакта. Единственное действие, которое #464 завёл своими руками, — и по самой скучной причине:
 * подтверждающий экран Point не рисует, его показывает система, а имя человек допишет там же.
 *
 * Родилось не из желания добавить действие, а из мёртвой строки: карточка готовности писала
 * «✓ Сохранить контакт +380504327707» и не давала тапнуть. Теперь строка запускает вот это —
 * тем же путём, что любой пузырь (`Object → Intent → Capability → Realizer`).
 *
 * **Не спорит с «Добавить в контакты»** ([VCardCapability]): та работает с присланной карточкой
 * `.vcf`, эта — с номером, прочитанным на скриншоте. На объекте, который И есть vCard, вторая
 * молчит: два действия про контакт в одном списке — это шум, а не выбор.
 */
class SaveContactCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "contact"
    // Позади остальных действий над сущностями (12…16): «Сохранить контакт» появилось последним и
    // никого не двигает — порядок первого экрана владелец видел и принял.
    override val meta = CapabilityMeta(priority = 17)
    override fun label(state: ObjectState) = "Сохранить контакт"
    override fun accepts(state: ObjectState) =
        !state.has(Feature.HAS_VCARD) && (state.has(Feature.HAS_PHONE) || state.has(Feature.HAS_EMAIL))
    override fun produces(state: ObjectState) = state // терминальное
    override fun intents(state: ObjectState) = setOf(Intent.OPEN)

    companion object { val ID = CapabilityId("save-contact") }
}

class SaveContactRealizer @Inject constructor(
    private val extractor: EntityExtractor,
    private val inserter: ContactInserter,
) : Realizer {
    override val capabilityId = SaveContactCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val phone = contactValue(input, "phone", EntityType.PHONE)
                val email = contactValue(input, "email", EntityType.EMAIL)
                // Пустых рук не бывает: пузырь зажигается по признаку, а признак — по факту. Если
                // всё же нечем — честный отказ, а не открытый пустой экран контакта.
                val shown = phone ?: email ?: error("Ни телефона, ни почты не нашлось")
                inserter.insertContact(phone, email)
                ActionResult.Done("Сохраняю контакт: $shown")
            }.getOrElse {
                ActionResult.Failure(it.message ?: "Не удалось сохранить контакт", recoverable = true)
            }
        }

    /**
     * Сначала — **факт объекта** (`entity.phone`), и только потом повторное извлечение из текста.
     *
     * Порядок именно такой, потому что строка карточки показывает человеку факт: тапнув
     * «Сохранить контакт +380504327707», он обязан увидеть на системном экране тот же номер, а не
     * второй, найденный движком в той же странице. Значение и обещание должны совпадать по
     * построению, а не по совпадению.
     */
    private suspend fun contactValue(input: PointObject, key: String, type: EntityType): String? =
        input.metadata[META_ENTITY_PREFIX + key]?.takeIf { it.isNotBlank() }
            ?: firstEntity(extractor, input, type)
}

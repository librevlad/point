package com.point.executors

import com.point.core.flow.ACTION_SCHEMAS
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.ContactInserter
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.Realizer
import com.point.core.flow.personNameOf
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SaveContactCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "contact"

    // Шаг кончается карточкой в приложении контактов — сохраняет её человек (#1131):
    // возврат ни с чем гасит «Открыл карточку контакта», а не оставляет её висеть.
    override val meta = CapabilityMeta(priority = 17, handsOff = true)
    override fun label(state: ObjectState) = "Сохранить контакт"
    override fun accepts(state: ObjectState) =
        !state.has(Feature.HAS_VCARD) && (state.has(Feature.HAS_PHONE) || state.has(Feature.HAS_EMAIL))
    override fun produces(state: ObjectState) = state
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
                phone ?: email ?: error("Ни телефона, ни почты не нашлось")

                // Всё знание о человеке — в карточку (#679): имя и адрес Point уже
                // прочитал, человеку не нужно вписывать их заново.
                val contact = com.point.core.flow.NewContact(
                    name = personName(input),
                    phone = phone,
                    email = email,
                    address = input.metadata[META_ENTITY_PREFIX + "address"]?.takeIf { it.isNotBlank() },
                )
                inserter.insertContact(contact)

                // Карточка ОТКРЫТА, а не сохранена: сохранит человек (#679/#674).
                ActionResult.Done(contact.name?.let { "Открыл карточку контакта: $it" }
                    ?: "Открыл карточку контакта — допишите и сохраните")
            }.getOrElse {
                ActionResult.Failure(it.message ?: "Не удалось открыть карточку контакта", recoverable = true)
            }
        }

    /**
     * Имя человека — из знания объекта (#993, решение владельца «имя из графа»).
     *
     * Раньше именем становилась первая попавшаяся сторона объекта: на наклейке с двумя
     * сторонами в карточку уезжал не хозяин сохраняемого номера, а тот, чей ключ оказался
     * в metadata раньше. Кого сохраняют — решает знание: чьё это знание карточки, а если
     * хозяин не назван — единственный человек объекта.
     *
     * Какое знание уезжает в карточку, объявлено один раз — схемой самого действия: второй
     * список тех же полей рядом с исполнителем расходился бы с ней молча.
     */
    private fun personName(input: PointObject): String? = personNameOf(
        input.metadata,
        ACTION_SCHEMAS.firstOrNull { it.runs == capabilityId }?.fields?.map { it.key }.orEmpty(),
    )

    private suspend fun contactValue(input: PointObject, key: String, type: EntityType): String? =
        input.metadata[META_ENTITY_PREFIX + key]?.takeIf { it.isNotBlank() }
            ?: com.point.core.flow.firstEntity(extractor, input, type)
}

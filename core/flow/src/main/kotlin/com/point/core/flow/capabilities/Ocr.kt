package com.point.core.flow.capabilities

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState

/**
 * Чтение снимка — одна способность на телефон и компьютер; исполнители у каждого свои.
 *
 * Слово о дороге чтения — от того, кто на этом устройстве читает (#1021, решение владельца).
 * Прежде вторая строка «сначала на телефоне, потом спрошу про сервис» была зашита здесь, в
 * общем словаре, и компьютер показывал её как свою, хотя своего чтения у него нет и идти он
 * может только в сервис. Словарь дороги не знает — её называет тот, кто собирает словарь
 * для своего устройства.
 */
class OcrCapability(

    /** Вторая строка под действием — как здесь пойдёт чтение; `null` — исполнитель не сказал. */
    private val promise: String? = null,
) : Capability {
    override val id = ID
    override val icon = "ocr"

    // Отвечает на вопрос «что написано на кадре»: при уже прочитанном уходит вниз (#1119).
    override val meta = CapabilityMeta(
        cost = Cost.FREE,
        latency = Latency.SLOW,
        answers = com.point.core.flow.KnownCapabilities.IMAGE_TEXT,
    )

    override fun label(state: ObjectState) = "Распознать текст"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    override fun yields(state: ObjectState) = ActionYield.New(ObjectKind.TEXT, promise)

    companion object { val ID = CapabilityId("ocr") }
}

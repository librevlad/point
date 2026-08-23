package com.point.core.flow.capabilities

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.OfficeAlwaysHere
import com.point.core.flow.OfficeOrgan
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.isFileBacked

class QrCapability  : Capability {
    override val id = ID
    override val icon = "qr"
    override val meta = CapabilityMeta(priority = 45)
    override fun label(state: ObjectState) = "QR-код"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT || state.kind == ObjectKind.URL
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)
    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("qr") }
}

class ArchiveCapability  : Capability {
    override val id = ID
    override val icon = "unzip"

    override val meta = CapabilityMeta(latency = Latency.FAST, revealsInside = true)
    override fun label(state: ObjectState) = "Распаковать"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.ZIP
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.COLLECTION)

    companion object { val ID = CapabilityId("archive") }
}

/**
 * «Извлечь текст» у офисного документа — знание, а не новый объект (#995, решение владельца).
 *
 * Текст документа рождал второй объект, который жил своей жизнью: у него не было ни имени
 * документа, ни его знания, а сам документ оставался «непрочитанным». Текст ложится на сам
 * документ тем же ключом, каким ложится любое чтение (#1157).
 */
class OfficeCapability  : Capability {
    override val id = ID
    override val icon = "office"

    override val meta = CapabilityMeta(latency = Latency.FAST, revealsInside = true)
    override fun label(state: ObjectState) = "Извлечь текст"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.OFFICE
    override fun produces(state: ObjectState) = state.with(Feature.HAS_TEXT)
    override fun yields(state: ObjectState) = ActionYield.Same(TEXT_OF_DOCUMENT_NOTE)

    // Прочитать документ — понять его: знание о том же объекте механически читается как
    // «ничего не вернёт», и намерение приходится называть вслух (как у расшифровки, #1097).
    override fun intents(state: ObjectState) = setOf(Intent.UNDERSTAND)

    companion object { val ID = CapabilityId("office") }
}

/** Что обещает «Извлечь текст» на обеих поверхностях: текст остаётся у самого документа. */
const val TEXT_OF_DOCUMENT_NOTE = "текст документа · без сети"

class ImageCapability  : Capability {
    override val id = ID
    override val icon = "compress"

    override val meta = CapabilityMeta(latency = Latency.FAST)

    override fun label(state: ObjectState) = "Сжать"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)

    companion object { val ID = CapabilityId("image") }
}

/**
 * «Дать ссылку» — ссылку выдаёт сервер Point, а он знает только своих (#1022).
 *
 * [signedIn] — есть ли на этом устройстве аккаунт Point прямо сейчас. Без него действие
 * не исчезает: человек должен видеть, чего он лишён, и услышать это по тапу — вместо
 * согласия на отправку, которая всё равно не состоится.
 */
class DropLinkCapability(
    private val signedIn: () -> Boolean = { true },
) : Capability {
    override val id = ID
    override val icon = "link"
    override val meta = CapabilityMeta(
        priority = 35,
        cost = Cost.FREE,
        latency = Latency.SLOW,
        network = true,

        // Ссылка остаётся у того, кто её выдал (#1034, #1106): нажали на телефоне — ссылка
        // нужна на телефоне, а не в буфере компьютера, куда заодно уезжал и сам объект.
        resultStaysHere = true,
    )

    override fun label(state: ObjectState) = "Дать ссылку"

    override fun accepts(state: ObjectState) =
        state.kind.isFileBacked && state.kind != ObjectKind.URL

    // «Выложен» — знание об объекте (#1071): ссылка и срок ложатся на исходник, узел
    // ссылки рождается находкой. Человек остаётся у своего объекта.
    override fun produces(state: ObjectState) = state

    override fun intents(state: ObjectState) = setOf(com.point.core.model.Intent.PREPARE)

    override fun yields(state: ObjectState) =
        com.point.core.model.ActionYield.Same("ссылка на сутки · файл уйдёт на сервер Point")

    override fun wontWorkNow(state: ObjectState): String? =
        if (signedIn()) null else NEEDS_ACCOUNT_FOR_LINK

    companion object { val ID = CapabilityId("drop-link") }
}

/** Ссылку выдаёт сервер Point — без аккаунта её выдавать некому (#1022, решение владельца). */
const val NEEDS_ACCOUNT_FOR_LINK = "Войдите в аккаунт — ссылка выдаётся через сервер Point"

/**
 * «В PDF» — и для офисного документа тоже, но только настоящим конвертером (#403).
 *
 * Пересказа больше нет: офисный файл превращается в PDF, когда есть орган, который сделает
 * это слайд в слайд. Органа нет — действие не исчезает, а называет, чего не хватает.
 */
class PdfCapability(private val office: OfficeOrgan = OfficeAlwaysHere) : Capability {
    override val id = ID
    override val icon = "pdf"

    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) =
        if (state.kind == ObjectKind.PDF) "Извлечь текст" else "В PDF"
    override fun accepts(state: ObjectState) =
        state.kind in setOf(ObjectKind.IMAGE, ObjectKind.TEXT) ||
            (state.kind == ObjectKind.OFFICE && office.missing() == null) ||

            (state.kind == ObjectKind.PDF && !state.has(Feature.IS_IMAGE_PDF))

    /** Органа нет — «Почти доступно» с причиной, а не тишина в списке действий. */
    override fun missing(state: ObjectState): String? =
        if (state.kind == ObjectKind.OFFICE) office.missing() else null

    /** Текст PDF — знание самого документа, а не второй объект рядом с ним (#995). */
    override fun produces(state: ObjectState) =
        if (state.kind == ObjectKind.PDF) state.with(Feature.HAS_TEXT) else ObjectState(ObjectKind.PDF)

    override fun yields(state: ObjectState) = when (state.kind) {
        ObjectKind.PDF -> ActionYield.Same(TEXT_OF_DOCUMENT_NOTE)
        else -> ActionYield.New(ObjectKind.PDF)
    }

    // Из PDF достают текст, чтобы понять документ; из снимка делают PDF, чтобы его отправить.
    override fun intents(state: ObjectState) =
        if (state.kind == ObjectKind.PDF) setOf(Intent.UNDERSTAND) else setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("pdf") }
}

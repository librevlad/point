package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.PcRemoteAction

/** Умения компьютера. Общие способности он делит с телефоном, а не заводит свои копии. */
fun desktopCapabilities(): Set<Capability> = setOf(
    PcOpenCapability(), PcCopyCapability(), PcRevealCapability(), PcSaveAsCapability(),
    PcDownloadCapability(), PcToPhoneCapability(), PcPrintCapability(),
    PcOpenLinkCapability(),

    // «Понять»/«Перевести»/«Спросить AI» на компьютере убраны (#701, решение
    // владельца «Убрать, ПК — только исполнитель»): результат для человека тот
    // же, что и на телефоне, — компьютер не должен быть отдельной дверью к
    // тому же самому. Остаются действия, привязанные к месту исполнения.
    PcTranscribeCapability(),

    PcEntitiesCapability(),

    // Сканированный PDF читается одним действием и здесь (#1014): страницы рисует pdfbox,
    // читает существующее облачное чтение, знание ложится на сам PDF.
    PcReadDocumentCapability(),
) + com.point.core.flow.capabilities.sharedCapabilities()

/**
 * Имена умений, привязанных к месту исполнения: бумага, файл и окно выходят на компьютере,
 * и человеку это нужно знать до тапа (ADR-0001 §7).
 *
 * Всё остальное едет телефону своим именем. Приписка «на ПК» ко всему подряд ставила в
 * список вторую строку на то же самое умение (#628, решение владельца «одна способность —
 * одна кнопка»).
 */
private val namedByPlace = mapOf(
    "pc-open" to "Открыть на компьютере",
    "pc-copy" to "В буфер компьютера",
    "pc-reveal" to "Показать в папке на компьютере",
    "pc-save-as" to "Сохранить на компьютере",
    "pc-print" to "Напечатать на компьютере",
    "pc-download" to "Скачать видео на компьютер",
    "pc-open-link" to "Открыть в браузере на компьютере",
)

/** Имя, под которым умение компьютера видно в списке на телефоне. */
fun phoneFacingLabel(action: PcRemoteAction): String = namedByPlace[action.id] ?: action.label

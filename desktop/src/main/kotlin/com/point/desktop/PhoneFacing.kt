package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.advertisedActions

/**
 * Умения компьютера. Общие способности он делит с телефоном, а не заводит свои копии; дорогу
 * чтения снимка в общем словаре называет сам (#1021) — словарь общий, слово — исполнителя.
 */
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
) + com.point.core.flow.capabilities.sharedCapabilities(ocrPromise = OCR_ON_PC_PROMISE)

/**
 * Имена умений, привязанных к месту исполнения: бумага, файл и окно выходят на компьютере,
 * и человеку это нужно знать до тапа (ADR-0001 §7).
 *
 * Всё остальное едет телефону своим именем. Приписка «на ПК» ко всему подряд ставила в
 * список вторую строку на то же самое умение (#628, решение владельца «одна способность —
 * одна кнопка»). Место зовётся одним словом — «компьютер» (#1094): две формы одного
 * места в одном списке читались как два разных места.
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

/**
 * Умения компьютера в том виде, в каком их получает телефон: объявление плюс имена по месту.
 * Одна точка и для провода (Main), и для сторожей (#1094) — тест смотрит на то же, что едет,
 * а не собирает объявление заново рядом.
 */
fun phoneFacingActions(capabilities: Collection<Capability>): List<PcRemoteAction> =
    advertisedActions(capabilities).map { it.copy(label = phoneFacingLabel(it)) }

/** Имя, под которым умение компьютера видно в списке на телефоне. */
private fun phoneFacingLabel(action: PcRemoteAction): String = namedByPlace[action.id] ?: action.label

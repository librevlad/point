package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.advertisedActions

/**
 * Умения компьютера. Общие способности он делит с телефоном, а не заводит свои копии; дорогу
 * чтения снимка в общем словаре называет сам (#1021) — словарь общий, слово — исполнителя.
 *
 * [signedIn] — есть ли на компьютере аккаунт Point (#1022): «Дать ссылку» без него не
 * сработает, и человек узнаёт это по тапу, а не после согласия на отправку.
 */
fun desktopCapabilities(
    /** Есть ли на компьютере ключ для расшифровки (#1379): название действия само скажет, если нет. */
    speechReady: com.point.core.flow.SpeechReadiness = com.point.core.flow.SpeechReadiness { emptyList() },

    // Последним — чтобы `desktopCapabilities { signedIn }` по-прежнему читалось как раньше.
    signedIn: () -> Boolean = { true },
): Set<Capability> = setOf(
    PcOpenCapability(), PcCopyCapability(), PcRevealCapability(), PcSaveAsCapability(),
    PcDownloadCapability(), PcToPhoneCapability(), PcPrintCapability(),
    PcOpenLinkCapability(),

    // «Понять», «Перевести», «AI» и «Расшифровать» на компьютере есть (#1379, решение владельца
    // 01.09.2026: «пк должен все уметь не хуже телефона»). Это отменило ограничительную часть
    // #701 («ПК — только исполнитель»): одна способность — один код в `:core:flow`, у каждой
    // поверхности свои органы. Органы живут в `Main.kt`; расшифровка объявлена здесь, потому
    // что это же объявление едет телефону и читается сторожами.
    com.point.core.flow.TranscribeCapability(speechReady),

    PcEntitiesCapability(),

    // Сканированный PDF читается одним действием и здесь (#1014): страницы рисует pdfbox,
    // читает существующее облачное чтение, знание ложится на сам PDF.
    pcReadDocument(),
) + com.point.core.flow.capabilities.sharedCapabilities(
    ocrPromise = OCR_ON_PC_PROMISE,
    signedIn = signedIn,
)

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
    advertisedActions(capabilities).map { it.copy(label = phoneFacingLabel(it.id, it.label)) }

/**
 * Имя, под которым умение компьютера видно человеку на телефоне.
 *
 * Одно место и для объявления, и для слов об исходе (#1073): человек нажал «Сохранить на
 * компьютере», и отказ обязан назвать нажатое, а не то, как это же умение зовётся здесь.
 */
fun phoneFacingLabel(id: String, own: String): String = namedByPlace[id] ?: own

/**
 * Умение, уходящее из круга, объявляется недоступным, пока режим закрыл дорогу наружу (#1269).
 *
 * Режим — это ответ человека, данный заранее, и он известен ещё в момент объявления. Значит
 * причина видна телефону до тапа, а не приходит отказом после напрасного ожидания. Своя,
 * более точная причина сильнее: «на компьютере нет yt-dlp» она не затирает.
 *
 * Мерка — та же, которой откажет сам компьютер (`DesktopState.wayOutClosed`), и другой она
 * быть не вправе: объявление обязано совпасть с тем, что случится по тапу. Телефонная
 * мерка мягче, и взять её сюда значило бы обещать телефону работу, которой не будет.
 */
fun withWayOutClosed(
    actions: List<PcRemoteAction>,
    level: com.point.core.flow.PrivacyLevel,
): List<PcRemoteAction> {
    if (com.point.core.flow.allowedAt(level, com.point.core.flow.AI_CHAIN_PRIVACY)) return actions
    val why = com.point.core.flow.chainClosedBy(level)
    return actions.map { if (it.leavesCircle && it.unavailable == null) it.copy(unavailable = why) else it }
}

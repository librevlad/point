package com.point.desktop

import java.io.File

/**
 * Знание, отправляемое телефону, — текстом, а не путём к файлу на этом компьютере (#1097, #811).
 *
 * `ocr.text.ref` — путь в файловой системе ПК; на телефоне он не открывается. Отправляют
 * телефону три места: `RelayRequests.replyFor` (срочный ответ на просьбу), `Outbox.add`
 * (исходящий объект) и `DesktopState.lateOutcomeMeta` → `Outbox.addOutcome` (исход, которому
 * срочно ответить некому: работа не уложилась в бюджет ответа либо просьба пролежала в ящике
 * дольше, чем её ждали, #1321). Первые два делали эту замену каждое своей копией правила,
 * третье не делало её вовсе: телефон получал неоткрываемый путь и рядом
 * `investigated.<работа> = found` — текста у человека нет, а вопрос закрыт, и повторно
 * предложить работу уже некому. Расшифровка голосового идёт как раз третьим местом: бюджет
 * синхронного ответа 10 с (`DesktopState.runRemoteActionNow`), сервису дано до 180 с.
 */
internal fun packedForTravel(meta: Map<String, String>): Map<String, String> {
    val ref = com.point.core.flow.textRefForTravel(meta) ?: return meta
    val text = runCatching {
        File(ref).takeIf(File::isFile)?.readText()?.take(com.point.core.flow.READ_TEXT_TRAVEL_LIMIT)
    }.getOrNull()
    return com.point.core.flow.knowledgePackedForTravel(meta, text)
}

package com.point.core.model

/**
 * The user's intent — the middle term of the Point model
 * `Object → Intent → Resolver → Capability → Object`.
 *
 * The user chooses a **goal**, not a mechanism: which capability / executor /
 * backend fulfils it (local, cloud, ICG) is the system's concern. This is the
 * stability contract for the UX — swapping a local OCR for an ICG one must not
 * change what the user sees here.
 *
 * Понять (understand what this object is / says) · Подготовить (prepare a new form
 * of it) · Открыть (open/view it in another app) · Отправить (send it onward, i.e.
 * share/save out). Open is deliberately its own goal — viewing here is not sending
 * away. Declaration order is display order.
 */
enum class Intent { UNDERSTAND, PREPARE, OPEN, SEND }

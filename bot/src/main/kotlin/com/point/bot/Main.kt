package com.point.bot

import com.point.core.flow.Capability
import com.point.core.flow.Realizer
import com.point.core.model.ObjectKind
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Point as a Telegram bot (#92): long-polling loop over the same Capability/Realizer
 * engine the phone and desktop run. Secrets come from local.properties via `:bot:run`
 * (system properties) — never compiled in. Forward an object to the bot → understanding
 * + an inline keyboard of actions → tap → result → continue.
 */
fun main() = runBlocking {
    val token = System.getProperty("TELEGRAM_BOT_TOKEN").orEmpty()
    require(token.isNotBlank()) {
        "TELEGRAM_BOT_TOKEN не задан в local.properties (создайте бота у @BotFather)"
    }
    val geminiKey = System.getProperty("GEMINI_API_KEY").orEmpty()
    val models = System.getProperty("GEMINI_MODELS", "gemini-pro-latest,gemini-flash-latest,gemini-flash-lite-latest")
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    val scratch = File(System.getProperty("java.io.tmpdir"), "point-bot").apply { mkdirs() }
    val llm = BotLlm(geminiKey, models, File(scratch, "ai"))

    // The bot's registry: LLM actions (Понять/Перевести/Собрать данные) + instant QR.
    val capabilities: Set<Capability> = setOf(
        LlmBotCapability("understand", "Понять", setOf(ObjectKind.TEXT, ObjectKind.IMAGE, ObjectKind.PDF), 10),
        LlmBotCapability("translate", "Перевести", setOf(ObjectKind.TEXT, ObjectKind.IMAGE), 12),
        LlmBotCapability("collect", "Собрать данные", setOf(ObjectKind.TEXT, ObjectKind.IMAGE), 14),
        ExcelBotCapability(),
        QrMakeCapability(),
        QrReadCapability(),
    )
    val realizers: Set<Realizer> = setOf(
        LlmBotRealizer("understand", "Кратко и ясно перескажи суть. Если это чек/таблица/документ — извлеки ключевое.", llm),
        LlmBotRealizer("translate", "Переведи на русский (а если текст уже русский — на английский). Верни только перевод.", llm),
        LlmBotRealizer("collect", "Собери все полезные данные и сгруппируй списком с заголовками: имена, организации, телефоны, почты, ссылки, адреса, даты, суммы. Дословно.", llm),
        ExcelBotRealizer(llm, File(scratch, "xlsx")),
        QrMakeRealizer(File(scratch, "qr")),
        QrReadRealizer(File(scratch, "qr")),
    )

    val api = HttpTelegramApi(token)
    val engine = BotEngine(api, BotRegistry(capabilities), BotResolver(realizers), File(scratch, "chats"))

    println("Point-бот запущен. Ожидаю сообщения…")
    var offset = 0L
    while (true) {
        val updates = runCatching { api.getUpdates(offset) }.getOrNull()
        if (updates == null) { delay(2_000); continue }
        highestUpdateId(updates)?.let { offset = it + 1 }
        for (update in parseUpdates(updates)) {
            runCatching { engine.onUpdate(update) }
                .onFailure { println("update ${update.updateId} failed: ${it.message}") }
        }
    }
}

package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.Realizer
import com.point.core.flow.qrMatrix
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Телефоны, почты, ссылки, суммы, даты, карты — списком, который можно скопировать целиком.
 *
 * На телефоне каждая находка становится отдельным объектом с собственными действиями («позвонить»,
 * «написать»). На компьютере таких действий нет: звонить с него некуда, а писать он умеет тем же
 * способом, что и всё остальное. Поэтому здесь честно проще — список, а не иллюзия выбора.
 */
/**
 * То, ради чего человек приходит в Point, — теперь и на компьютере (#585).
 *
 * До этого ПК умел восемь вещей, и все восемь — про работу с файлом как с файлом: открыть,
 * показать в папке, напечатать, сохранить. Разобрать содержимое он не умел вовсе, и человек,
 * приславший документ с телефона, получал на компьютере меньше, чем имел в руке.
 *
 * Здесь четыре действия над **содержимым**: найти в тексте нужное, понять, перевести, спросить.
 * Плюс QR — единственное, что Point умеет отдать глазами, а не файлом.
 *
 * Все они работают ровно на том же шве, что и остальные (`Capability` + `Realizer`), и потому
 * автоматически едут на телефон как удалённые действия компьютера.
 */

// --- Найти в тексте ------------------------------------------------------------------------

class PcEntitiesCapability : Capability {
    override val id = CapabilityId("pc-entities")
    override val icon = "search"
    override val meta = CapabilityMeta(priority = 25)
    override fun label(state: ObjectState) = "Найти в тексте"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
}

class PcEntitiesRealizer(
    private val extractor: EntityExtractor,
    private val outbox: Outbox,
) : Realizer {
    override val capabilityId = CapabilityId("pc-entities")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult = runCatching {
        val text = File(input.uri.value).takeIf(File::isFile)?.readText()
            ?: return ActionResult.Failure("Файла объекта нет на диске", recoverable = false)
        val found = extractor.extract(text)
        if (found.isEmpty()) {
            // Пустой результат — не поломка, и говорить о нём надо словами: «ничего не нашлось»
            // человек понимает, а пустой файл на выходе выглядит как сбой.
            return ActionResult.Failure("В тексте не нашлось ни телефона, ни почты, ни суммы", recoverable = false)
        }
        val report = report(found)
        val file = File.createTempFile("pc-found-", ".txt").apply { writeText(report) }
        // Результат — новый объект ЗДЕСЬ (#595), а не письмо на телефон: работа продолжается на
        // том устройстве, где человек её начал.
        ActionResult.Success(
            com.point.core.model.ResultObject(
                type = ObjectKind.TEXT,
                mime = "text/plain",
                uri = ScratchRef(file.absolutePath),
                metadata = mapOf("name" to ("Найдено · " + summary(found))),
            ),
        )
    }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось разобрать текст", recoverable = true) }

    /** Список по разделам — так его читают глазами и так же копируют кусками. */
    private fun report(found: List<Entity>): String = buildString {
        EntityType.entries.forEach { type ->
            val values = found.filter { it.type == type }.map(Entity::value).distinct()
            if (values.isEmpty()) return@forEach
            appendLine(title(type))
            values.forEach { appendLine(it) }
            appendLine()
        }
    }.trim()

    private fun summary(found: List<Entity>): String =
        found.groupBy { it.type }.entries
            .joinToString(", ") { (type, list) -> title(type).lowercase() + " — " + list.size }

    private fun title(type: EntityType): String = when (type) {
        EntityType.PHONE -> "Телефоны"
        EntityType.EMAIL -> "Почты"
        EntityType.URL -> "Ссылки"
        EntityType.ADDRESS -> "Адреса"
        EntityType.DATE_TIME -> "Даты"
        EntityType.PAYMENT_CARD -> "Карты"
        EntityType.MONEY -> "Суммы"
    }
}

// --- AI: понять, перевести, спросить --------------------------------------------------------

/**
 * Общая часть трёх AI-действий: они отличаются только промптом и словами на кнопке.
 *
 * `network = true` и `auth = true` — не украшение: телефон по этим признакам сам дописывает
 * «нужен ключ» и не обещает бесплатного там, где нужен чужой сервис.
 */
private fun aiMeta(priority: Int) = CapabilityMeta(
    priority = priority,
    latency = Latency.SLOW,
    network = true,
    auth = true,
)

class PcUnderstandCapability : Capability {
    override val id = CapabilityId("pc-understand")
    override val icon = "ai"
    override val meta = aiMeta(26)
    override fun label(state: ObjectState) = "Понять"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
}

class PcTranslateCapability : Capability {
    override val id = CapabilityId("pc-translate")
    override val icon = "translate"
    override val meta = aiMeta(27)
    override fun label(state: ObjectState) = "Перевести"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
}

class PcAskCapability : Capability {
    override val id = CapabilityId("pc-ask")
    override val icon = "ai"
    override val meta = aiMeta(28)
    override fun label(state: ObjectState) = "Спросить AI"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
}

/**
 * Один исполнитель на три действия — промпт приходит параметром.
 *
 * Отдельных классов у них нет намеренно: разница между «понять» и «перевести» — это одна строка
 * текста, и заводить ради неё три почти одинаковых файла значит заводить три места, где эта
 * строка однажды разойдётся с телефоном.
 */
class PcAiRealizer(
    override val capabilityId: CapabilityId,
    private val llm: LlmClient,
    private val prompt: String,
    private val outbox: Outbox,
    private val resultName: String,
) : Realizer {

    /** Уходит к чужому сервису, и это сказано вслух: телефон спросит согласие ДО отправки — там,
     *  где человек, а не здесь (контракт 06.08.2026, граница молчаливого выбора). */
    override val meta = com.point.core.flow.RealizerMeta(kind = com.point.core.flow.RealizerKind.CLOUD)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult = runCatching {
        if (!llm.configured) {
            // Ключа нет — это не ошибка сети, и чинится это не повтором. Говорим, где именно
            // его прописать: человек стоит у той самой машины, где лежит файл настроек.
            return ActionResult.Failure(
                "Ключ AI не задан — впишите его в ~/.point-pc/config строкой ai.key=…",
                recoverable = false,
            )
        }
        // Уточнение человека (если он его дал) идёт ПОСЛЕ задания: так модель читает сначала
        // работу, потом пожелание, а не наоборот.
        val full = if (amendment.isNullOrBlank()) prompt else prompt + "\n" + amendment
        val result = llm.run(input, full)
        ActionResult.Success(result.copy(metadata = result.metadata + ("name" to resultName)))
    }.getOrElse { ActionResult.Failure(it.message ?: "Сервис AI не ответил", recoverable = true) }
}

/** Задания моделям — теми же словами, что на телефоне: один продукт говорит одинаково. */
object PcPrompts {
    const val UNDERSTAND =
        "Прочитай текст и выпиши по-русски: о чём он, какие в нём есть суммы, даты, сроки, " +
            "имена и контакты, и что от человека требуется. Без вступлений и без пересказа " +
            "целиком — только суть и факты."

    const val TRANSLATE =
        "Переведи текст на русский язык. Если он уже на русском — переведи на английский. " +
            "Верни только перевод, без пояснений."

    const val ASK = "Ответь на вопрос человека по этому тексту. Коротко и по делу."
}

// --- QR ------------------------------------------------------------------------------------

/**
 * Ссылка с компьютера — на экран телефона за одно движение.
 *
 * Самый частый случай: на компьютере открыта страница, забрать её в телефон нечем. QR решает это
 * без сети, без аккаунта и без единого чужого сервиса — просто картинкой на экране.
 *
 * Матрица считается общим кодом (`qrMatrix` в `:core:flow`), а рисуется здесь: холст — свойство
 * платформы, и на телефоне его рисует Compose, а на компьютере — обычный PNG.
 */
class PcQrRealizer(private val outbox: Outbox) : Realizer {
    override val capabilityId = com.point.core.flow.capabilities.QrCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult = runCatching {
        val text = File(input.uri.value).takeIf(File::isFile)?.readText()?.trim()
            ?: return ActionResult.Failure("Файла объекта нет на диске", recoverable = false)
        val matrix = qrMatrix(text)
            ?: return ActionResult.Failure(
                "В QR помещается короткая ссылка или строка — этот текст длиннее",
                recoverable = false,
            )
        val quiet = 4
        val side = (matrix.size + quiet * 2) * SCALE
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, side, side)
        g.color = java.awt.Color.BLACK
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix[x, y]) {
                    g.fillRect((x + quiet) * SCALE, (y + quiet) * SCALE, SCALE, SCALE)
                }
            }
        }
        g.dispose()
        val file = File.createTempFile("pc-qr-", ".png")
        ImageIO.write(image, "png", file)
        ActionResult.Success(
            com.point.core.model.ResultObject(
                type = ObjectKind.IMAGE,
                mime = "image/png",
                uri = ScratchRef(file.absolutePath),
                metadata = mapOf("name" to "QR-код"),
            ),
        )
    }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось собрать QR", recoverable = true) }

    private companion object {
        /** Модуль в точках: восемь — читается камерой телефона с экрана ноутбука без прицеливания. */
        const val SCALE = 8
    }
}

// --- Офисный документ → текст ----------------------------------------------------------------

/**
 * Текст из docx, xlsx и pptx — без Word и без чужих библиотек (#585).
 *
 * Внутри такого файла лежит обычный zip с XML, и весь разбор — это `java.util.zip` плюс регулярка.
 * Класс общий с телефоном (`:core:flow`), поэтому один и тот же документ на обоих устройствах
 * читается одинаково — расходиться нечему.
 *
 * Дальше с этим текстом работают все остальные действия ПК: найти контакты, понять, перевести.
 * Именно ради этого действие и стоит первым в очереди на офисном объекте.
 */
class PcOfficeTextRealizer(
    private val extractor: com.point.core.flow.OfficeTextExtractor,
    private val outbox: Outbox,
) : Realizer {
    override val capabilityId = com.point.core.flow.capabilities.OfficeCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult = runCatching {
        val text = extractor.extractText(input)
        if (text.isBlank()) {
            // Старый бинарный .doc — не OOXML, и внутри него не zip. Честнее сказать это, чем
            // отдать пустой файл, который человек прочитает как поломку.
            return ActionResult.Failure(
                "В этом документе текста не нашлось — старые .doc и .xls компьютер не открывает",
                recoverable = false,
            )
        }
        val file = File.createTempFile("pc-office-", ".txt").apply { writeText(text) }
        ActionResult.Success(
            com.point.core.model.ResultObject(
                type = ObjectKind.TEXT,
                mime = "text/plain",
                uri = ScratchRef(file.absolutePath),
                metadata = mapOf("name" to "Текст документа"),
            ),
        )
    }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось прочитать документ", recoverable = true) }
}

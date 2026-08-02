package com.point.data

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Второй бесплатный читатель на подделках (#280). Асинхронный контракт (загрузил → опрашивай)
 * проверяется целиком, включая то, что незавершённая задача не выдаётся за пустую страницу.
 */
class LlamaParseAtomRecognizerTest {

    private val created = HttpResult(200, """{"id":"pjb-1","status":"PENDING"}""")

    private fun pending() = HttpResult(200, """{"job":{"id":"pjb-1","status":"PENDING"}}""")

    private fun completed(items: String) = HttpResult(
        200,
        """
        {"job":{"id":"pjb-1","status":"COMPLETED"},
         "items":{"pages":[{"page_number":1,"page_width":500,"page_height":400,"items":[$items]}]}}
        """.trimIndent(),
    )

    private fun reader(http: FakeHttpFiles, frames: OutboundFrames = FakeOutboundFrames(sentFrame())) =
        LlamaParseAtomRecognizer(http, frames, "free-key", "https://api.cloud.llamaindex.ai")

    @Test
    fun `сначала загрузка, потом опрос — пока задача не готова`() = runTest {
        var polls = 0
        val http = FakeHttpFiles(
            onPost = { created },
            onGet = { if (++polls < 2) pending() else completed("""{"type":"text","value":"11004","bbox":{"x":100,"y":100,"w":100,"h":50}}""") },
        )
        val layer = reader(http).read(pageObject)

        assertEquals("https://api.cloud.llamaindex.ai/api/v2/parse/upload", http.posts.single().url)
        assertEquals("Bearer free-key", http.posts.single().headers["Authorization"])
        assertEquals("page.jpg", http.posts.single().file("file")?.fileName)
        assertTrue(http.posts.single().field("configuration")?.contains("cost_effective") == true)
        assertEquals(2, http.gets.size) // первый ответ был PENDING — ждём, а не сдаёмся
        assertTrue(http.gets.all { it.contains("/api/v2/parse/pjb-1") && it.contains("expand=items") })
        assertEquals(listOf("11004"), layer.atoms.map { it.text })
    }

    @Test
    fun `координаты приводятся к сырому кадру`() = runTest {
        val http = FakeHttpFiles(
            onPost = { created },
            onGet = { completed("""{"type":"text","value":"11004","bbox":{"x":100,"y":100,"w":100,"h":50}}""") },
        )
        val box = reader(http).read(pageObject).atoms.single().box

        // Отчёт 500×400 → посланная копия 1000×800 (×2) → сырой файл (×2 по sample).
        assertEquals(400f, box.left, 0.01f)
        assertEquals(400f, box.top, 0.01f)
        assertEquals(800f, box.right, 0.01f)
        assertEquals(600f, box.bottom, 0.01f)
    }

    @Test
    fun `рамка понимается и объектом, и массивом спанов, и старым написанием bBox`() = runTest {
        val items = listOf(
            """{"type":"text","value":"один","bbox":{"x":100,"y":100,"w":100,"h":50}}""",
            """{"type":"text","value":"два","bbox":[{"x":0,"y":0,"w":10,"h":10},{"x":20,"y":0,"w":10,"h":10}]}""",
            """{"type":"text","value":"три","bBox":{"x":10,"y":10,"w":10,"h":10}}""",
        ).joinToString(",")
        val http = FakeHttpFiles(onPost = { created }, onGet = { completed(items) })
        val layer = reader(http).read(pageObject)

        assertEquals(listOf("один", "два", "три"), layer.atoms.map { it.text })
        assertEquals(listOf("lp0", "lp1", "lp2"), layer.atoms.map { it.id })
        // Несколько спанов схлопываются в накрывающий прямоугольник, а не теряются.
        val second = layer.atoms[1].box
        assertEquals(0f, second.left, 0.01f)
        assertEquals(120f, second.right, 0.01f)
    }

    @Test
    fun `таблица без value берёт текст из разметки`() = runTest {
        val http = FakeHttpFiles(
            onPost = { created },
            onGet = { completed("""{"type":"table","md":"| 11004 | 12 |","bbox":{"x":0,"y":0,"w":10,"h":10}}""") },
        )
        assertEquals("| 11004 | 12 |", reader(http).read(pageObject).atoms.single().text)
    }

    @Test
    fun `элемент без рамки не становится атомом, но его текст не теряется`() = runTest {
        val items = """{"type":"text","value":"ВЕДОМОСТЬ"},{"type":"text","value":"11004","bbox":{"x":0,"y":0,"w":10,"h":10}}"""
        val http = FakeHttpFiles(onPost = { created }, onGet = { completed(items) })
        val layer = reader(http).read(pageObject)

        assertEquals(listOf("11004"), layer.atoms.map { it.text })
        assertTrue(layer.text.contains("ВЕДОМОСТЬ"))
    }

    @Test
    fun `429 при опросе — отказ, а не пустая страница`() = runTest {
        val http = FakeHttpFiles(onPost = { created }, onGet = { HttpResult(429, "slow down") })
        val error = runCatching { reader(http).read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message?.contains("429") == true)
    }

    @Test
    fun `402 не ведёт в кассу`() = runTest {
        val http = FakeHttpFiles(onPost = { HttpResult(402, "add a card") })
        val error = runCatching { reader(http).read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message?.contains("покупать не идём") == true)
    }

    @Test
    fun `сорвавшаяся задача говорит об этом вслух`() = runTest {
        val failed = HttpResult(200, """{"job":{"id":"pjb-1","status":"ERROR","error_message":"bad page"}}""")
        val http = FakeHttpFiles(onPost = { created }, onGet = { failed })
        val error = runCatching { reader(http).read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message?.contains("задача не выполнена") == true)
        assertTrue(error?.message?.contains("bad page") == true)
    }

    /**
     * Ответ, списанный с примера в документации сервиса **дословно** — страница в пунктах
     * (612×792), рамка массивом спанов, у заголовка рамки нет вовсе.
     *
     * Смысл отдельного теста: все остальные фикстуры здесь сочинил тот же человек, который писал
     * разбор, и они сходятся друг с другом по построению. Урок #233 ровно про это — движок,
     * проверенный на собственноручно набранном входе, разошёлся с живым кадром по трём пунктам
     * из четырёх. Фикстура из чужого первоисточника этот круг размыкает.
     */
    @Test
    fun `ответ в форме из документации разбирается как есть`() = runTest {
        val documented = """
            {"job":{"id":"pjb-1","status":"COMPLETED"},
             "items":{"pages":[{"page_number":1,"page_width":612.0,"page_height":792.0,
               "items":[
                 {"type":"heading","level":1,"value":"Document Title","md":"# Document Title"},
                 {"type":"text","value":"11004","bbox":[{"x":72.0,"y":100.0,"w":200.0,"h":12.0}]}
               ],"success":true}]}}
        """.trimIndent()
        val http = FakeHttpFiles(onPost = { created }, onGet = { HttpResult(200, documented) })
        val layer = reader(http).read(pageObject)

        // Заголовок из примера рамки не несёт — адрес ему не выдумываем, но текст остаётся.
        assertEquals(listOf("11004"), layer.atoms.map { it.text })
        assertTrue(layer.text.contains("Document Title"))

        // Страница объявлена в пунктах (612×792), послали копию 1000×800, сырой файл вдвое больше.
        // Закрепляется здесь ПРАВИЛО — нормировка по объявленной системе. Останется ли система
        // пропорциональной посланному кадру, правило не решает: уложи сервис снимок 4:3 в лист
        // Letter полями, и понадобились бы ещё и отступы, которых он не сообщает. Это и есть та
        // единственная вещь, которую закроет только живой ключ с наложением рамок на 23.jpg.
        val box = layer.atoms.single().box
        assertEquals(72f / 612f * 1000f * 2f, box.left, 0.05f)
        assertEquals(100f / 792f * 800f * 2f, box.top, 0.05f)
        assertEquals(272f / 612f * 1000f * 2f, box.right, 0.05f)
    }

    /**
     * Язык страницы уезжает вместе с настройками — иначе на русской ведомости сервис читает
     * латиницей и второе чтение оказывается такой же кашей, как первое.
     *
     * Коды здесь **свои** (`ru`), не тессерактовые (`rus`) соседнего ридера: у этого сервиса свой
     * перечень языков, и одинаково выглядящая строка молча читала бы не тот язык.
     */
    @Test
    fun `настройки несут язык страницы, и код языка — из перечня этого сервиса`() = runTest {
        val http = FakeHttpFiles(
            onPost = { created },
            onGet = { completed("""{"type":"text","value":"11004","bbox":{"x":0,"y":0,"w":10,"h":10}}""") },
        )
        reader(http).read(pageObject)

        val configuration = JSONObject(http.posts.single().field("configuration").orEmpty())
        val languages = configuration
            .getJSONObject("processing_options")
            .getJSONObject("ocr_parameters")
            .getJSONArray("languages")

        assertEquals("cost_effective", configuration.getString("tier"))
        assertEquals(2, languages.length())
        assertEquals("ru", languages.getString(0)) // порядок значащий — основной язык первым
        assertEquals("en", languages.getString(1))
    }

    /**
     * Уверенность берётся у сервиса, а не ставится единицей «на всякий случай»: он её сообщает
     * (поле `confidence` у рамки), а подменить её единицей — сгладить ровно ту неуверенность,
     * по которой человек и решает, куда идти перечитывать.
     */
    @Test
    fun `уверенность приезжает от сервиса, а несколько спанов схлопываются худшим`() = runTest {
        val items = listOf(
            """{"type":"text","value":"уверенно","bbox":[{"x":0,"y":0,"w":10,"h":10,"confidence":0.94}]}""",
            """{"type":"text","value":"спорно","bbox":[{"x":0,"y":20,"w":10,"h":10,"confidence":0.9},
                 {"x":20,"y":20,"w":10,"h":10,"confidence":0.31}]}""",
            """{"type":"text","value":"молчит","bbox":[{"x":0,"y":40,"w":10,"h":10}]}""",
        ).joinToString(",")
        val http = FakeHttpFiles(onPost = { created }, onGet = { completed(items) })
        val atoms = reader(http).read(pageObject).atoms

        assertEquals(0.94f, atoms[0].confidence, 0.001f)
        // Минимум, а не среднее: атом надёжен настолько, насколько надёжен его худший кусок.
        assertEquals(0.31f, atoms[1].confidence, 0.001f)
        // Не сообщил — единица, и она означает «не сообщил», а не «уверен».
        assertEquals(1f, atoms[2].confidence, 0.001f)
    }

    @Test
    fun `пустой ключ выключает слой`() = runTest {
        assertFalse(LlamaParseAtomRecognizer(FakeHttpFiles(), FakeOutboundFrames(sentFrame()), "", "").configured)
    }
}

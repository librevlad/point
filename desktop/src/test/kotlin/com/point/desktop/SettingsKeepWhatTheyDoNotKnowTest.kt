package com.point.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsKeepWhatTheyDoNotKnowTest {

    @get:Rule val temp = TemporaryFolder()

    private fun dir() = temp.newFolder("point-" + System.nanoTime())

    @Test fun `запись не теряет того, о чём не спрашивали`() {
        val home = dir()
        File(home, "config").writeText(
            """
            speech.key=gsk-секрет
            speech.model=whisper-large-v3
            ocr.key=ocr-секрет
            ai.url=https://свой.адрес/v1/chat/completions
            secrets.at=12345
            """.trimIndent(),
        )
        val store = FilePcConfig(home)

        store.save(store.load().copy(name = "Рабочий ноутбук"))

        // Секреты сверяются прочтением, а не сырым текстом файла: на диске они теперь
        // защищены ключом пользователя (#1095), но терять их запись по-прежнему не смеет.
        val kept = store.load()
        assertTrue("потерян ключ расшифровки", kept.speech.key == "gsk-секрет")
        assertTrue("потерян ключ чтения", kept.ocr.key == "ocr-секрет")

        val after = File(home, "config").readText()
        assertTrue("потерян свой адрес сервиса: $after", "свой.адрес" in after)
        assertTrue("потеряна метка ключей: $after", "12345" in after)
        assertTrue("не записано имя: $after", "Рабочий ноутбук" in after)
    }

    @Test fun `стёртый человеком ключ исчезает из файла`() {

        val home = dir()
        File(home, "config").writeText("name=ПК\nai.key=sk-старый")
        val store = FilePcConfig(home)

        store.save(store.load().copy(aiKeys = com.point.core.flow.UserAiKeys.NONE))

        assertTrue("стёртый ключ остался в файле", "sk-старый" !in File(home, "config").readText())
    }

    @Test fun `пустой ключ не пишется строкой «ключ есть, но сломан»`() {
        val home = dir()
        val store = FilePcConfig(home)

        store.save(store.load())

        assertTrue("пустой ключ уехал в файл", "ai.key=" !in File(home, "config").readText())
    }

    @Test fun `прочитанное совпадает с записанным`() {
        val home = dir()
        val store = FilePcConfig(home)

        store.save(store.load().copy(name = "Ноутбук у окна"))

        assertEquals("Ноутбук у окна", FilePcConfig(home).load().name)
    }
}

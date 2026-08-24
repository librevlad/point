package com.point.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Пара звуков-порталов (#650): уход с телефона и приход на компьютер — один тембр.
 * Тембр совпадает только потому, что файл общий, — это и проверяется.
 */
class PortalSoundTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `звук прибытия лежит в ресурсах компьютера и читается как звук`() {
        val bytes = javaClass.getResourceAsStream("/point_portal_in.wav")?.readBytes()

        assertNotNull("файл звука не попал в ресурсы компьютера", bytes)
        val stream = javax.sound.sampled.AudioSystem
            .getAudioInputStream(java.io.ByteArrayInputStream(bytes!!))
        assertEquals("звук должен быть тем же форматом, что и остальные у Point", 44100f, stream.format.sampleRate, 0f)
        assertTrue("звук-портал длиннее секунды — это уже не отклик", stream.frameLength < 44100)
        stream.close()
    }

    @Test
    fun `тот же файл берёт телефон — иначе тембр не совпадёт`() {
        val shared = java.io.File(repo, "data/src/main/res/raw/point_portal_in.wav")

        assertTrue(
            "звук компьютера обязан браться из общей папки с телефоном: ${shared.absolutePath}",
            shared.isFile,
        )
        assertEquals(
            "ресурс компьютера разошёлся с файлом телефона",
            shared.readBytes().size,
            javaClass.getResourceAsStream("/point_portal_in.wav")!!.readBytes().size,
        )
    }

    @Test
    fun `выключенный звук молчит, включённый — пробует играть`() {
        var asked = 0
        val muted = JvmPortalSound { asked++; false }

        muted.arrived()

        assertEquals("настройка обязана спрашиваться на каждом звуке", 1, asked)
    }

    @Test
    fun `по умолчанию звук включён, а отказ переживает перезапись настроек`() {
        val config = FilePcConfig(temp.newFolder("point"))

        assertTrue("владелец просил включённым по умолчанию", config.load().sound)

        config.save(config.load().copy(sound = false))
        assertEquals(false, FilePcConfig(temp.root.resolve("point")).load().sound)
    }
}

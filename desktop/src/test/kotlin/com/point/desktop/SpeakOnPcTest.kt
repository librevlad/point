package com.point.desktop

import com.point.core.flow.Spoken
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** «Озвучить» на компьютере — голосами Windows (#442, #1379). */
class SpeakOnPcTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Windows понарошку: голоса — строкой, запись — байтами в файл, что попросили. */
    private inner class FakeWindows(private val voices: String) {
        var spokenWith: Map<String, String>? = null
        var spokenText: String? = null

        fun run(script: String, env: Map<String, String>): Pair<Int, String> {
            if (env.isEmpty()) return 0 to voices
            spokenWith = env
            spokenText = File(env.getValue("POINT_TTS_TEXT")).readText()
            File(env.getValue("POINT_TTS_OUT")).writeBytes(ByteArray(200) { 1 })
            return 0 to ""
        }
    }

    @Test fun `читает голосом на языке текста`() = runBlocking {
        val windows = FakeWindows("Microsoft Zira\ten\nMicrosoft Irina\tru\n")
        val voice = PcTextToSpeech(windows = true, run = windows::run)
        val into = File(tmp.root, "вслух.wav").absolutePath

        val text = "Привет, мир"
        val said = voice.speak(text, "ru", into)

        assertTrue("$said", said is Spoken.Done)
        assertEquals("Microsoft Irina", windows.spokenWith!!["POINT_TTS_VOICE"])
        assertEquals(text, windows.spokenText)
        assertEquals(listOf("en", "ru"), voice.voices())
    }

    @Test fun `голоса на языке нет — отказ называет язык, а не молчит`() = runBlocking {
        val windows = FakeWindows("Microsoft Zira\ten\n")
        val voice = PcTextToSpeech(windows = true, run = windows::run)

        val said = voice.speak("Привіт", "uk", File(tmp.root, "x.wav").absolutePath)

        assertTrue("$said", said is Spoken.Refused)
        assertEquals("до голоса не дошло: текст читать было нечем", null, windows.spokenWith)
    }

    @Test fun `голос ничего не записал — отказ, а не пустая запись`() = runBlocking {
        val voice = PcTextToSpeech(windows = true) { _, env -> if (env.isEmpty()) 0 to "Microsoft Irina\tru" else 0 to "" }
        val into = File(tmp.root, "пусто.wav")

        val said = voice.speak("Текст", "ru", into.absolutePath)

        assertTrue("$said", said is Spoken.Refused)
        assertTrue("пустая запись осталась", !into.exists())
    }

    @Test fun `не Windows — голосов нет, и действие скажет, что читать нечем`() = runBlocking {
        val voice = PcTextToSpeech(windows = false) { _, _ -> error("PowerShell вне Windows не зовут") }

        assertEquals(emptyList<String>(), voice.voices())
    }
}

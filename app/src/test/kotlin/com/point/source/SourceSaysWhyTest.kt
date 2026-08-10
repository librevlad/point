package com.point.source

import android.content.Context
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Источник говорит, зачем ему разрешение, — до системного окна (#568).
 *
 * Прежде тап по «Месту» сразу поднимал запрос точного местоположения: первый разговор о доверии
 * шёл от имени Android, сухо и без причины.
 */
class SourceSaysWhyTest {

    private val asking = listOf(
        LocationSource(),
        VoiceSource(),
        CameraSource(),
    )

    @Test fun `у источника, которому нужно разрешение, есть подпись про пользу`() {
        asking.forEach { source ->
            val what = source.what
            assertNotNull("«${source.label}» просит разрешение молча", what)
            assertTrue("подпись пуста у «${source.label}»", what!!.isNotBlank())
        }
    }

    @Test fun `подпись говорит про пользу, а не про само разрешение`() {
        val forbidden = listOf("разрешение", "доступ", "permission", "нужен доступ")

        asking.forEach { source ->
            val what = source.what.orEmpty().lowercase()
            forbidden.forEach { word ->
                assertTrue(
                    "«${source.label}» объясняет разрешение вместо пользы: ${source.what}",
                    word !in what,
                )
            }
        }
    }

    @Test fun `источнику без разрешений подпись не выдумывается`() {
        val plain = object : ObjectSource {
            override val id = "plain"
            override val label = "Без разрешений"
            override fun isAvailable(context: Context) = true
            override suspend fun request(context: Context) = null
            override suspend fun read(context: Context, data: android.content.Intent?) = null
        }

        assertNull("источнику без разрешений нечего объяснять", plain.what)
    }
}

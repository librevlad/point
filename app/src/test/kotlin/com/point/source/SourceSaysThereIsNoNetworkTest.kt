package com.point.source

import android.content.Context
import android.content.Intent
import com.point.core.flow.DropInbox
import com.point.core.flow.DropInboxBox
import com.point.core.flow.DropWait
import com.point.core.flow.NO_INTERNET_NOTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Источник, которому нужна сеть, говорит об этом до тапа (#759).
 *
 * Правило «без сети сетевое действие называет причину» (#569) жило только у списка действий
 * над объектом. Источники — другой список, и правило туда не дошло: «Принять файл» при
 * выключенной сети выглядел обычной строкой, а отказывал уже после нажатия. Одно и то же
 * положение человека на двух экранах было описано по-разному.
 */
class SourceSaysThereIsNoNetworkTest {

    private fun source(
        name: String,
        needsNetwork: Boolean,
        promise: String? = null,
    ) = object : ObjectSource {
        override val id = name
        override val label = name
        override val what = promise
        override val network = needsNetwork
        override fun isAvailable(context: Context) = true
        override suspend fun request(context: Context): Intent? = null
        override suspend fun read(context: Context, data: Intent?): Produced? = null
    }

    @Test
    fun `без сети сетевой источник называет причину`() {
        val note = sourceNote(source("Принять файл", needsNetwork = true), online = false)

        assertEquals(NO_INTERNET_NOTE, note)
    }

    @Test
    fun `причина вытесняет обещание, а не приписывается к нему`() {
        val receive = source("Принять файл", needsNetwork = true, promise = "получить файл со ссылки")

        assertEquals(NO_INTERNET_NOTE, sourceNote(receive, online = false))
    }

    @Test
    fun `с сетью источник обещает пользу, как и раньше`() {
        val receive = source("Принять файл", needsNetwork = true, promise = "получить файл со ссылки")

        assertEquals(receive.what, sourceNote(receive, online = true))
    }

    @Test
    fun `источнику без сети чужая причина не приписывается`() {
        val camera = source("Камера", needsNetwork = false, promise = "снять и разобрать")

        assertEquals(camera.what, sourceNote(camera, online = false))
    }

    @Test
    fun `источнику без разрешений и без сети подпись не выдумывается`() {
        assertNull(sourceNote(source("Буфер обмена", needsNetwork = false), online = false))
    }

    /**
     * Правило бесполезно, пока источник о своей потребности молчит: «Принять файл» ходит за
     * ссылкой на сервер Point, и это его свойство, а не свойство экрана.
     */
    @Test
    fun `«Принять файл» объявляет, что выйдет в сеть`() {
        assertTrue("источник не объявил сеть — правило до него не дойдёт", ReceiveFileSource(SilentInbox).network)
    }

    private companion object {

        val SilentInbox = object : DropInbox {
            override suspend fun open(): com.point.core.flow.DropOpen =
                com.point.core.flow.DropOpen.Refused("не нужен этому тесту")
            override suspend fun await(box: DropInboxBox, target: (name: String) -> String): DropWait =
                DropWait.Empty

            override suspend fun ack(box: DropInboxBox, fileId: String) = Unit
        }
    }
}

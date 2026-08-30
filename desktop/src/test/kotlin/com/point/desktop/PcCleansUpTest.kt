package com.point.desktop

import com.point.core.flow.COPY_LIFETIME_MS
import com.point.core.flow.PcExecFields
import com.point.core.flow.PcOutboxEntry
import com.point.core.flow.PcResultFields
import com.point.core.flow.RelayRpc
import com.point.core.flow.decodePcOutbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PcCleansUpTest {

    @get:Rule val temp = TemporaryFolder()

    private val сутки = COPY_LIFETIME_MS

    @Test fun `пролежавшее дольше срока исчезает с диска`() {
        val dir = temp.newFolder("Point")
        val old = File(dir, "Screenshot_Authenticator.jpg").apply {
            writeText("коды")
            setLastModified(System.currentTimeMillis() - 11 * сутки)
        }
        val inbox = Inbox(dir)

        val removed = inbox.sweep(System.currentTimeMillis() - сутки)

        assertEquals(1, removed)
        assertFalse("снимок с кодами остался лежать", old.exists())
    }

    @Test fun `свежее не трогается`() {
        val dir = temp.newFolder("Point2")
        val fresh = File(dir, "чек.jpg").apply { writeText("чек") }

        Inbox(dir).sweep(System.currentTimeMillis() - сутки)

        assertTrue("убрали то, с чем человек ещё работает", fresh.exists())
    }

    @Test fun `принесённое человеком не удаляется никогда`() {

        val mine = temp.newFolder("Point3")
        val his = temp.newFolder("Документы")
        val doc = File(his, "договор.docx").apply {
            writeText("договор")
            setLastModified(System.currentTimeMillis() - 30 * сутки)
        }
        val item = Inbox(mine).addFile(doc.absolutePath)

        Inbox(mine).sweep(System.currentTimeMillis() - сутки)

        assertEquals("объект перестал указывать на файл человека", doc.absolutePath, item.obj.uri.value)
        assertTrue("Point удалил файл, который человек всего лишь показал", doc.exists())
    }

    @Test fun `снимки экрана и скачанное убираются тоже`() {

        val dir = temp.newFolder("Point4")
        val screens = File(dir, "screens").apply { mkdirs() }
        val shot = File(screens, "экран.png").apply {
            writeText("снимок")
            setLastModified(System.currentTimeMillis() - 3 * сутки)
        }

        Inbox(dir).sweep(System.currentTimeMillis() - сутки)

        assertFalse("снимок экрана пережил уборку", shot.exists())
    }

    /**
     * Очередь на телефон живёт по тому же сроку, что и всё брошенное на компьютере (#1317,
     * решение владельца 29.08.2026, вариант A).
     *
     * Проверяется тем, что видит человек: списком, который телефон получает, спросив
     * компьютер. У владельца в очереди лежали три `фів.vcf` и запись-исход от 20 августа и
     * пары от 26–27-го — и телефон предлагал забрать семь объектов после одной отправки.
     * Правило одно и на вещи, и на записи-исходы: для человека это одинаково брошенное.
     */
    @Test fun `очередь на телефон забывает брошенное, и телефон больше не зовёт за ним`() {
        val dir = temp.newFolder("Point6")
        val outbox = Outbox(File(dir, "outbox"))
        val abandoned = outbox.add(textArrival("вещь", "фів.vcf").obj)
        val words = outbox.addOutcome(
            mapOf(PcResultFields.OUTCOME to PcResultFields.DONE, PcExecFields.HOME to "объект-в-который-не-войдут"),
        )
        val awaited = outbox.add(textArrival("свежая", "чек.jpg").obj)
        listOf(abandoned, words).forEach { id ->
            File(File(dir, "outbox"), "$id.meta").setLastModified(System.currentTimeMillis() - 10 * сутки)
        }

        val forgotten = outbox.forgetOlderThan(System.currentTimeMillis() - COPY_LIFETIME_MS)

        assertEquals("убрано не всё брошенное — правило разошлось на вещи и записи-исходы", 2, forgotten.toLong())
        assertEquals(
            "телефон зовёт человека за тем, чего он уже не ждёт",
            listOf("чек.jpg"),
            whatThePhoneIsOffered(outbox).map { it.meta["name"] },
        )
        assertNull("байты брошенной вещи остались лежать в очереди", outbox.file(abandoned))
        assertNotNull("уборка унесла и то, за чем человек ещё придёт", outbox.file(awaited))
    }

    /** Тот же вопрос, с которым телефон приходит к компьютеру за списком «с компьютера». */
    private fun whatThePhoneIsOffered(outbox: Outbox): List<PcOutboxEntry> {
        val reply = RelayRequests(
            remoteActions = { emptyList() },
            outbox = outbox,
            onPhoneCaps = {},
            clipboardGet = { null },
            clipboardSet = {},
            onObject = { _, _, _, _, _ -> null },
        ).answer(RelayRpc.OUTBOX, emptyMap(), ByteArray(0))!!
        return decodePcOutbox(String(reply.body, Charsets.UTF_8))
    }

    @Test fun `выход уносит всё, включая свежее`() {

        val dir = temp.newFolder("Point5")
        File(dir, "чек.jpg").writeText("чек")
        File(dir, "screens").apply { mkdirs() }.let { File(it, "экран.png").writeText("снимок") }

        Inbox(dir).wipe()

        assertEquals("после выхода осталось чужое", 0, dir.listFiles()?.size ?: 0)
    }
}

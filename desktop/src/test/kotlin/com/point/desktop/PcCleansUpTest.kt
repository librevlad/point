package com.point.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Компьютер убирает за собой (#602).
 *
 * На телефоне рабочая копия стирается по окончании работы — это инвариант. На компьютере правила
 * не было вовсе: у владельца в папке лежали 23 файла за 11 дней, среди них снимок приложения с
 * кодами двухфакторной аутентификации и переписка. Через эту дверь идёт то же самое, что через
 * телефон; разница была только в том, что здесь оно оставалось.
 *
 * Судится диск, а не вызовы: урок #446 — тест, считающий обращения к уборщику, зелен и тогда,
 * когда файл на месте.
 */
class PcCleansUpTest {

    @get:Rule val temp = TemporaryFolder()

    private val сутки = 24L * 60 * 60 * 1000

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
        // Перетащенный мышью файл оборачивается НА МЕСТЕ и в рабочую папку не копируется. Уборка
        // ходит только по своей папке, поэтому чужой документ ей недостижим — но проверить это
        // надо здесь: цена ошибки — удалённый файл человека.
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
        // Их кладёт сюда сам Point, и они те же по составу: на снимке экрана бывает всё, что было
        // на экране.
        val dir = temp.newFolder("Point4")
        val screens = File(dir, "screens").apply { mkdirs() }
        val shot = File(screens, "экран.png").apply {
            writeText("снимок")
            setLastModified(System.currentTimeMillis() - 3 * сутки)
        }

        Inbox(dir).sweep(System.currentTimeMillis() - сутки)

        assertFalse("снимок экрана пережил уборку", shot.exists())
    }

    @Test fun `выход уносит всё, включая свежее`() {
        // «Выйти» — это не уборка по сроку, а конец работы этого человека на этом компьютере.
        val dir = temp.newFolder("Point5")
        File(dir, "чек.jpg").writeText("чек")
        File(dir, "screens").apply { mkdirs() }.let { File(it, "экран.png").writeText("снимок") }

        Inbox(dir).wipe()

        assertEquals("после выхода осталось чужое", 0, dir.listFiles()?.size ?: 0)
    }
}

package com.point.desktop

import com.point.core.flow.UserAiKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Секреты компьютера не лежат на диске открытым текстом (#1095).
 *
 * Телефон свои шифрует; на ПК ключи AI, речи и OCR, токен устройства и приватный ключ
 * связки лежали в `~/.point-pc` как есть. Защита — ключом пользователя системы (DPAPI);
 * там, где её нет, значение честно остаётся голым, а старые голые файлы читаются как
 * раньше и защищаются при первой записи.
 */
class SecretsDoNotLieBareTest {

    @get:Rule val temp = TemporaryFolder()

    @Test fun `защищённое возвращается тем же значением`() {
        val secret = "sk-" + System.nanoTime()
        assertEquals(secret, SecretVault.reveal(SecretVault.protect(secret)))
    }

    @Test fun `голое старое значение читается как есть`() {
        val bare = "sk-" + System.nanoTime()
        assertEquals(bare, SecretVault.reveal(bare))
    }

    @Test fun `на этой системе ключ AI не лежит в файле открытым текстом`() {
        assumeTrue("без DPAPI защита честно не обещается", SecretVault.active)
        val store = FilePcConfig(temp.newFolder("point-home"))
        val was = store.load()

        val secret = "sk-" + System.nanoTime()
        store.save(was.copy(aiKeys = was.aiKeys.with(UserAiKey("openrouter", secret))))

        val onDisk = java.io.File(temp.root, "point-home/config").readText()
        assertFalse("ключ виден в файле как есть", onDisk.contains(secret))
        assertEquals("прочитанный обратно ключ перестал совпадать", secret, store.load().aiKeys.keyFor("openrouter"))
    }

    @Test fun `настройка без секрета в файле осталась читаемой`() {
        val store = FilePcConfig(temp.newFolder("point-home"))
        store.save(store.load().copy(sound = false))

        assertTrue(java.io.File(temp.root, "point-home/config").readText().contains("sound"))
    }
}

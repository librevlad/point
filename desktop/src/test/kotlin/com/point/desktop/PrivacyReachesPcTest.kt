package com.point.desktop

import com.point.core.flow.AI_CHAIN_PRIVACY
import com.point.core.flow.AccountSettings
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.allowedAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Режим отправки доезжает до компьютера и там действует (#893).
 *
 * Человек выбирал на телефоне «Только на этом устройстве», а компьютер про этот выбор не
 * знал вовсе: `PrivacyLevel` не встречался в его коде ни разу. Расшифровка записи и чтение
 * снимка — облачные и здесь, значит объект уходил на чужой сервер вопреки сказанному.
 *
 * Здесь — дорога выбора: доезжает ли он до компьютера и что означает сам по себе. Что режим
 * на компьютере действует, доказано поведением окна в `CloudConsentTest`, а на просьбе
 * телефона — в `PhoneRequestStaysInTheModeTest` (#1247, #1269). Прежде на этом месте стояла
 * сверка порядка двух строк в исходнике `DesktopState.kt`: она пережила бы и пропавший
 * `return`, и неверную константу, и переименование самого метода.
 */
class PrivacyReachesPcTest {

    @get:Rule val temp = TemporaryFolder()

    private fun config() = FilePcConfig(temp.newFolder("point-home"))

    @Test
    fun `выбор с телефона доезжает и переживает перезапуск`() {
        val home = temp.newFolder("point-home-travel")

        FilePcConfig(home).applyAccountSettings(
            AccountSettings(privacy = PrivacyLevel.DEVICE_ONLY, at = 1_000),
        )

        assertEquals(PrivacyLevel.DEVICE_ONLY, FilePcConfig(home).load().privacy)
    }

    @Test
    fun `выбранный здесь режим уезжает на телефон`() {
        val store = config()
        store.save(store.load().copy(privacy = PrivacyLevel.NO_TRAINING))

        assertEquals(PrivacyLevel.NO_TRAINING, store.accountSettings().privacy)
    }

    @Test
    fun `в режиме «только на этом устройстве» облако закрыто, а не спрошено`() {
        assertTrue(!allowedAt(PrivacyLevel.DEVICE_ONLY, AI_CHAIN_PRIVACY))
        assertTrue(!allowedAt(PrivacyLevel.NO_TRAINING, AI_CHAIN_PRIVACY))
        assertTrue(allowedAt(PrivacyLevel.FREE_FIRST, AI_CHAIN_PRIVACY))
    }

    @Test
    fun `слова режима не привязаны к телефону`() {
        PrivacyLevel.entries.forEach {
            assertTrue("«${it.title}» говорит про телефон на экране компьютера",
                !it.title.contains("телефон"))
            assertTrue("описание «${it.title}» говорит про телефон", !it.what.contains("с телефона"))
        }
    }
}

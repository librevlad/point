package com.point

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Point сам предлагает разрешить уведомления (#1449) — один раз и только тогда, когда есть кому стучать. */
class KnockOfferTest {

    @Test fun `компьютер в круге, уведомлений нет, не спрашивали — предлагается`() {
        assertTrue(knockOfferVisible(Build.VERSION_CODES.TIRAMISU, pcInCircle = true, knockOff = true, offered = false))
    }

    @Test fun `без компьютера стучать некому`() {
        assertFalse(knockOfferVisible(Build.VERSION_CODES.TIRAMISU, pcInCircle = false, knockOff = true, offered = false))
    }

    @Test fun `уже разрешено — говорить не о чем`() {
        assertFalse(knockOfferVisible(Build.VERSION_CODES.TIRAMISU, pcInCircle = true, knockOff = false, offered = false))
    }

    @Test fun `ответ человека второй раз не переспрашивается`() {
        assertFalse(knockOfferVisible(Build.VERSION_CODES.TIRAMISU, pcInCircle = true, knockOff = true, offered = true))
    }

    @Test fun `до Android 13 разрешения нет вовсе`() {
        assertFalse(knockOfferVisible(Build.VERSION_CODES.S_V2, pcInCircle = true, knockOff = true, offered = false))
    }
}

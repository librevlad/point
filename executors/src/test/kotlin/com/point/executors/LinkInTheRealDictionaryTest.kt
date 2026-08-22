package com.point.executors

import com.point.core.flow.AccountStore
import com.point.core.flow.OfficeAlwaysHere
import com.point.core.flow.capabilities.DropLinkCapability
import com.point.core.flow.capabilities.NEEDS_ACCOUNT_FOR_LINK
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.executors.di.CapabilityModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Дать ссылку» в том наборе, который телефон и правда раздаёт (#1022).
 *
 * Способность, собранная руками в тесте, доказывает только саму себя: правило про аккаунт
 * могло не доехать до боевого словаря, и человек по-прежнему проходил бы согласие ради
 * отправки, которой не будет. Здесь спрашивается ровно то, что кладёт в набор
 * `CapabilityModule`, — и спрашивается до тапа, как спросит экран.
 */
class LinkInTheRealDictionaryTest {

    private val image = ObjectState(ObjectKind.IMAGE)

    private fun link(account: AccountStore) =
        CapabilityModule.sharedCaps(OfficeAlwaysHere, account).first { it.id == DropLinkCapability.ID }

    @Test fun `в боевом наборе «Дать ссылку» без аккаунта называет причину до тапа`() {
        assertEquals(NEEDS_ACCOUNT_FOR_LINK, link(AccountForTests(null)).wontWorkNow(image))
    }

    @Test fun `в боевом наборе «Дать ссылку» с аккаунтом молчит и идёт прежним ходом`() {
        assertNull(link(AccountForTests(SOMEBODY)).wontWorkNow(image))
    }

    /** Прятать действие, ради которого и зовут войти, нельзя- человек не узнает, чего лишён. */
    @Test fun `без аккаунта действие остаётся на месте`() {
        assertTrue(link(AccountForTests(null)).accepts(image))
    }

    /**
     * Человек входит и выходит, не перезапуская Point: набор собирается один раз, а про
     * аккаунт спрашивают на каждый вопрос.
     */
    @Test fun `аккаунт спрашивается в момент вопроса, а не при сборке набора`() {
        val account = AccountForTests(null)
        val link = link(account)
        assertEquals(NEEDS_ACCOUNT_FOR_LINK, link.wontWorkNow(image))

        account.now = SOMEBODY

        assertNull("человек вошёл, а набор помнит прежнее", link.wontWorkNow(image))
    }
}

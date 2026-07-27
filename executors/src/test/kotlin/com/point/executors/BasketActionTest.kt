package com.point.executors

import com.point.core.flow.Basket
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «В корзину» (#96): a terminal drop into the progressive object — the flow ends,
 *  the accumulated pile opens later from Home as a regular COLLECTION. */
class BasketActionTest {

    private class FakeBasket : Basket {
        val added = mutableListOf<PointObject>()
        override suspend fun add(obj: PointObject): Int { added += obj; return added.size }
        override suspend fun items(): List<String> = added.map { it.uri.value }
        override suspend fun clear() = added.clear()
    }

    @Test
    fun `accepts everything except a collection`() {
        val cap = BasketCapability()
        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT)))
        assertTrue(cap.accepts(ObjectState(ObjectKind.IMAGE)))
        assertFalse(cap.accepts(ObjectState(ObjectKind.COLLECTION)))
        assertFalse(cap.meta.network) // on-device, instant
    }

    @Test
    fun `performs a terminal add and reports the new count`() = runTest {
        val basket = FakeBasket()
        val obj = PointObject("id", "text/plain", ScratchRef("/x"), ObjectState(ObjectKind.TEXT))

        val result = BasketRealizer(basket).perform(obj, null)

        assertTrue(result is ActionResult.Done)
        assertEquals(1, basket.added.size)
        assertTrue((result as ActionResult.Done).message.contains("1"))
    }
}

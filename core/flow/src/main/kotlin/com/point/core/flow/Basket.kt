package com.point.core.flow

import com.point.core.model.PointObject

/**
 * The progressive object (#96): a basket the user drops objects into one by one
 * («В корзину» is terminal), later opened from Home as one COLLECTION — actions
 * then apply to the pile, not to each item. The basket owns COPIES in the app's
 * own directory: scratch dies with every flow, the basket survives until an
 * explicit clear.
 */
interface Basket {

    /** Copy the object's bytes into the basket. Returns the new item count. */
    suspend fun add(obj: PointObject): Int

    /** Absolute file paths of the accumulated copies, in insertion order. */
    suspend fun items(): List<String>

    /** Drop all accumulated items. */
    suspend fun clear()
}

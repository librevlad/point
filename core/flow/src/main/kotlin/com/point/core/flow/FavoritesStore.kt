package com.point.core.flow

import com.point.core.model.CapabilityId
import com.point.core.model.FavoriteChain

/** Persistent saved chains. Applicability (does a chain fit the current object?)
 *  is decided by the registry — a chain fits if its first capability accepts. */
interface FavoritesStore {
    suspend fun save(name: String, steps: List<CapabilityId>): FavoriteChain
    suspend fun all(): List<FavoriteChain>
    suspend fun delete(id: String)
}

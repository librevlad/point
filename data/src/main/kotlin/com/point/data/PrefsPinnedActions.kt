package com.point.data

import android.content.Context
import com.point.core.flow.PinnedActions
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrefsPinnedActions @Inject constructor(
    @ApplicationContext private val context: Context,
) : PinnedActions {

    private val prefs by lazy { context.getSharedPreferences("pinned-actions", Context.MODE_PRIVATE) }

    override fun pinnedFor(kind: ObjectKind): CapabilityId? =
        runCatching { prefs.getString(kind.name, null)?.let { CapabilityId(it) } }.getOrNull()

    override fun all(): Map<ObjectKind, CapabilityId> = runCatching {
        prefs.all
            .mapNotNull { (kind, id) ->
                val value = (id as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ObjectKind.of(kind) to CapabilityId(value)
            }
            .toMap()
    }.getOrDefault(emptyMap())

    override suspend fun pin(kind: ObjectKind, id: CapabilityId): Unit = withContext(Dispatchers.IO) {
        prefs.edit().putString(kind.name, id.value).apply()
    }

    override suspend fun unpin(kind: ObjectKind): Unit = withContext(Dispatchers.IO) {
        prefs.edit().remove(kind.name).apply()
    }
}

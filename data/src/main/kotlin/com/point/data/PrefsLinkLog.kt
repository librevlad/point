package com.point.data

import android.content.Context
import com.point.core.flow.LinkLog
import com.point.core.flow.LinkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrefsLinkLog @Inject constructor(
    @ApplicationContext private val context: Context,
) : LinkLog {

    private val prefs by lazy { context.getSharedPreferences("pc_link", Context.MODE_PRIVATE) }

    override fun read(): LinkMonitor.Contact? = runCatching {
        val at = prefs.getLong(KEY_AT, 0L).takeIf { it > 0L } ?: return@runCatching null
        LinkMonitor.Contact(at)
    }.getOrNull()

    override fun write(contact: LinkMonitor.Contact) {
        runCatching { prefs.edit().putLong(KEY_AT, contact.at).apply() }
    }

    override fun clear() {
        runCatching { prefs.edit().remove(KEY_AT).apply() }
    }

    private companion object {
        const val KEY_AT = "last_at"
    }
}

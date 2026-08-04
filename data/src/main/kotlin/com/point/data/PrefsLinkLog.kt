package com.point.data

import android.content.Context
import com.point.core.flow.LinkLog
import com.point.core.flow.LinkMonitor
import com.point.core.flow.LinkPath
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Последний контакт с компьютером переживает перезапуск (#451).
 *
 * Два поля в prefs — время и путь. Больше и не нужно: монитор помнит один факт, а не журнал.
 *
 * Незнакомое название пути читается как «пути нет»: список путей может вырасти, и старая запись
 * не должна ронять экран. Тогда состояние выйдет «не отвечает» вместо «на связи» — самое
 * безобидное из возможных вранья, и его чинит первый же успешный запрос.
 */
@Singleton
class PrefsLinkLog @Inject constructor(
    @ApplicationContext private val context: Context,
) : LinkLog {

    private val prefs by lazy { context.getSharedPreferences("pc_link", Context.MODE_PRIVATE) }

    override fun read(): LinkMonitor.Contact? = runCatching {
        val at = prefs.getLong(KEY_AT, 0L).takeIf { it > 0L } ?: return@runCatching null
        val name = prefs.getString(KEY_PATH, null) ?: return@runCatching null
        val path = runCatching { LinkPath.valueOf(name) }.getOrNull() ?: return@runCatching null
        LinkMonitor.Contact(at, path)
    }.getOrNull()

    override fun write(contact: LinkMonitor.Contact) {
        runCatching {
            prefs.edit().putLong(KEY_AT, contact.at).putString(KEY_PATH, contact.path.name).apply()
        }
    }

    override fun clear() {
        runCatching { prefs.edit().remove(KEY_AT).remove(KEY_PATH).apply() }
    }

    private companion object {
        const val KEY_AT = "last_at"
        const val KEY_PATH = "last_path"
    }
}

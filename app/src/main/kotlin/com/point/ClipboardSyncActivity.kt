package com.point

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.point.core.flow.PcClipboardSync
import com.point.core.flow.PcPairings
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Shared clipboard with the PC (#161 «общий буфер»). Android forbids a background app from touching
 * the clipboard, so the QS tile launches this momentary foreground activity: it reads the phone
 * clipboard here (foreground = allowed), decides direction, syncs, and finishes — invisible.
 *
 * Direction without conflict-versioning: if the phone clipboard changed since the last sync, PUSH it
 * to the PC; otherwise PULL the PC's. So a copy on either device reaches the other on the next tap.
 */
@AndroidEntryPoint
class ClipboardSyncActivity : ComponentActivity() {

    @Inject lateinit var pcPairings: PcPairings
    @Inject lateinit var clipboardSync: PcClipboardSync
    private var handled = false

    // The clipboard may only be READ once this activity actually holds window focus (Android 10+) —
    // which is NOT yet true in onCreate. Reading there returns empty, so a fresh phone copy looked
    // like «nothing to push» and the tile always pulled instead. Sync on first real focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !handled) {
            handled = true
            lifecycleScope.launch {
                runCatching { sync() }.onFailure { toast("Не удалось синхронизировать") }
                finish()
            }
        }
    }

    private suspend fun sync() {
        val pairing = pcPairings.current() ?: return toast("Сначала подключите компьютер")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return toast("Буфер недоступен")
        val phoneClip = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val lastSynced = prefs.getString(KEY_LAST, "").orEmpty()

        if (phoneClip.isNotEmpty() && phoneClip != lastSynced) {
            // The phone copied something new — send it to the PC.
            if (clipboardSync.push(pairing, phoneClip)) {
                prefs.edit().putString(KEY_LAST, phoneClip).apply()
                toast("Буфер → компьютер")
            } else {
                toast("Компьютер недоступен")
            }
        } else {
            // Nothing new on the phone — take what the PC has.
            when (val pcClip = clipboardSync.pull(pairing)) {
                null -> toast("Компьютер недоступен")
                phoneClip, "" -> toast("Буфер уже синхронизирован")
                else -> {
                    cm.setPrimaryClip(ClipData.newPlainText("Point", pcClip))
                    prefs.edit().putString(KEY_LAST, pcClip).apply()
                    toast("Буфер ← компьютер")
                }
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val PREFS = "point-clipboard"
        const val KEY_LAST = "last-synced"
    }
}

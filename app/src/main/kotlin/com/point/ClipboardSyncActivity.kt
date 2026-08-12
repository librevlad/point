package com.point

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.point.core.flow.ClipFail
import com.point.core.flow.ClipPull
import com.point.core.flow.ClipPush
import com.point.core.flow.ClipboardPayload
import com.point.core.flow.PcClipboardSync
import com.point.core.flow.PcLinks
import com.point.core.flow.PcUnreachable
import com.point.core.flow.pcUnreachableText
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ClipboardSyncActivity : ComponentActivity() {

    @Inject lateinit var pcLinks: PcLinks
    @Inject lateinit var clipboardSync: PcClipboardSync
    private var handled = false

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
        val pc = pcLinks.current() ?: return toast(pcUnreachableText(PcUnreachable.NOT_IN_CIRCLE))
        val phone = readPhoneClipboard()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val lastSig = prefs.getString(KEY_LAST, "").orEmpty()
        val phoneSig = phone?.signature().orEmpty()

        if (phone != null && phoneSig != lastSig) {

            when (val sent = clipboardSync.push(pc, phone)) {
                is ClipPush.Sent -> {
                    prefs.edit().putString(KEY_LAST, phoneSig).apply()
                    toast(if (phone.isText) "Буфер → компьютер" else "Файл → компьютер")
                }
                is ClipPush.Unreachable -> toast(pcUnreachableText(PcUnreachable.PC_ASLEEP))
                is ClipPush.Failed -> toast(failText(sent.why))
            }
        } else {

            when (val answer = clipboardSync.pull(pc)) {
                is ClipPull.Unreachable -> toast(pcUnreachableText(PcUnreachable.PC_ASLEEP))
                is ClipPull.Failed -> toast(failText(answer.why))
                is ClipPull.Empty -> toast("Буфер уже синхронизирован")
                is ClipPull.Got -> {
                    val payload = answer.payload
                    if (payload.signature() == phoneSig) {
                        toast("Буфер уже синхронизирован")
                    } else {
                        setPhoneClipboard(payload)
                        prefs.edit().putString(KEY_LAST, payload.signature()).apply()
                        toast(if (payload.isText) "Буфер ← компьютер" else "Файл ← компьютер")
                    }
                }
            }
        }
    }

    private fun readPhoneClipboard(): ClipboardPayload? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        val uri = item.uri
        return if (uri != null) {
            runCatching {
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val name = displayName(uri) ?: "clip"
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
                ClipboardPayload(mime, name, bytes)
            }.getOrNull()
        } else {
            item.coerceToText(this)?.toString()?.takeIf { it.isNotEmpty() }?.let(ClipboardPayload::ofText)
        }
    }

    private fun setPhoneClipboard(payload: ClipboardPayload) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        if (payload.isText) {
            cm.setPrimaryClip(ClipData.newPlainText("Point", payload.text()))
        } else {

            val dir = File(filesDir, "scratch/clip").apply { mkdirs() }
            val safe = com.point.core.flow.safeFileName(payload.name, ifBlank = "clip")
            val file = File(dir, safe).apply { writeBytes(payload.bytes) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            cm.setPrimaryClip(ClipData.newUri(contentResolver, "Point", uri))
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && c.columnCount > 0) c.getString(0) else null
        }
    }.getOrNull()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun failText(why: ClipFail): String = when (why) {
        ClipFail.TOO_BIG -> pcUnreachableText(PcUnreachable.TOO_BIG)
        ClipFail.AUTH -> com.point.core.flow.PC_DEVICE_REVOKED
    }

    private companion object {
        const val PREFS = "point-clipboard"
        const val KEY_LAST = "last-synced"
    }
}

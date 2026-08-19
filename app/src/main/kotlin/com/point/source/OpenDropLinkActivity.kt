package com.point.source

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ссылка Point у получателя с Point открывается в Point (#1083, решение владельца).
 *
 * `https://point.leerio.app/d/…` раньше уходила в браузер: получатель с установленным
 * Point получал веб-страницу вместо объекта — без знания, происхождения и действий.
 * Теперь система отдаёт такую ссылку сюда (App Links, отпечатки — на сервере), файл
 * скачивается и входит обычной дверью объекта. Без Point ссылка остаётся веб-страницей.
 */
@AndroidEntryPoint
class OpenDropLinkActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.data?.toString()
        if (link.isNullOrBlank()) {
            finish()
            return
        }
        Toast.makeText(this, "Принимаю по ссылке…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val taken = withContext(Dispatchers.IO) { fetch(link) }
            if (taken == null) {
                Toast.makeText(
                    this@OpenDropLinkActivity,
                    "Не удалось принять по ссылке — проверьте интернет, ссылка живёт сутки",
                    Toast.LENGTH_LONG,
                ).show()
                finish()
                return@launch
            }
            val (file, mime, name) = taken
            startActivity(
                Intent(this@OpenDropLinkActivity, com.point.ShareActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
                    .putExtra(EXTRA_OBJECT_NAME, name),
            )
            finish()
        }
    }

    /** Скачанное — во временный файл приёма; дальше объект живёт копией в scratch, как все. */
    private fun fetch(link: String): Triple<File, String, String?>? = runCatching {
        val conn = (URL(withRaw(link)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 45_000
        }
        val code = conn.responseCode
        if (code !in 200..299) return null
        val mime = conn.contentType?.substringBefore(';')?.trim().orEmpty().ifBlank { "application/octet-stream" }
        val name = com.point.core.flow.fileNameFromDisposition(conn.getHeaderField("Content-Disposition"))
        val dir = File(cacheDir, "drop-in").apply { mkdirs() }
        val target = File(dir, "received-${System.currentTimeMillis()}")
        conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        Triple(target, mime, name)
    }.getOrNull()

    private fun withRaw(link: String): String =
        if (link.contains("?")) "$link&raw=1" else "$link?raw=1"

}

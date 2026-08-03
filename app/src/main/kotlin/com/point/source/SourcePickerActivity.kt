package com.point.source

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.point.ShareActivity
import com.point.core.ui.theme.PointTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * «Что превратить в объект?» — экран выбора источника из шторки (#246).
 *
 * Живёт поверх чужого приложения и исчезает, как только объект родился. Здесь же решается вопрос,
 * из-за которого этот экран вообще обязан быть активити, а не сервисом: буфер обмена Android
 * отдаёт только приложению на переднем плане (тот же приём, что у `ClipboardSyncActivity`).
 */
@AndroidEntryPoint
class SourcePickerActivity : ComponentActivity() {

    @Inject lateinit var sources: Set<@JvmSuppressWildcards ObjectSource>

    private var pending: ObjectSource? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val source = pending
            pending = null
            when {
                source == null -> finish()
                result.values.any { !it } -> {
                    // Отказ назван словами: молча закрыться — значит оставить человека гадать,
                    // сломалось оно или он сам только что запретил.
                    Toast.makeText(this, "Без этого доступа не получится", Toast.LENGTH_SHORT).show()
                    finish()
                }
                else -> launchSource(source)
            }
        }

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val source = pending
        pending = null
        if (source == null) {
            finish()
        } else {
            lifecycleScope.launch { deliver(source.read(this@SourcePickerActivity, result.data)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val visible = sources.filter { it.isAvailable(this) }.sortedBy { it.label }
        setContent {
            PointTheme {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Что превратить в объект?")
                    visible.forEach { source ->
                        Button(onClick = { start(source) }) { Text(source.label) }
                    }
                }
            }
        }
    }

    /**
     * Разрешение спрашивается по тапу и только то, которого нет: просить уже выданное — то самое
     * назойливое трение, от которого Point уходит.
     */
    private fun start(source: ObjectSource) {
        val granted = source.permissions.filter {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }.toSet()
        val missing = missingPermissions(source.permissions, granted)
        if (missing.isNotEmpty()) {
            pending = source
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        launchSource(source)
    }

    private fun launchSource(source: ObjectSource) {
        lifecycleScope.launch {
            val request = source.request(this@SourcePickerActivity)
            if (request == null) {
                deliver(source.read(this@SourcePickerActivity, null))
                return@launch
            }
            pending = source
            launcher.launch(request)
        }
    }

    /** Объект уходит в обычную дверь: шторка его не обрабатывает (#246). */
    private fun deliver(produced: Produced?) {
        if (produced == null) {
            finish() // отказ уже назван источником
            return
        }
        startActivity(
            Intent(this, ShareActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType(produced.mime)
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(produced.uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        finish()
    }
}

package com.point

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * «Компьютер» (#147): pair the phone with «Point для ПК». Manual host:port entry is
 * the always-working path (the emulator reaches the host PC as 10.0.2.2); the PC
 * confirms with its own dialog, so pairing is a handshake, not a secret exchange.
 */
@Composable
fun PairPcScreen(
    state: PcScreenState,
    onPair: (host: String, port: Int) -> Unit,
    onUnpair: () -> Unit,
    onClose: () -> Unit,
) {
    // `rememberSaveable` (#114): поворот телефона пересоздаёт экран — набранные адрес и порт
    // обязаны его пережить, иначе человек набирает их заново, не понимая, за что.
    var host by rememberSaveable { mutableStateOf(state.pairing?.host ?: "") }
    var port by rememberSaveable { mutableStateOf((state.pairing?.port ?: 8391).toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Компьютер", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (state.pairing != null) {
                // «Подключён» раньше означало только «мы когда-то познакомились» — адрес из
                // пейринга. Теперь рядом живое состояние: отвечает компьютер или молчит (#412).
                "${state.pairing.host}:${state.pairing.port}"
            } else {
                "Запустите «Point для ПК» и введите адрес из его окна"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.pairing != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                com.point.core.flow.linkLabel(state.link),
                style = MaterialTheme.typography.bodyMedium,
                color = when (state.link) {
                    is com.point.core.flow.LinkState.Live -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.height(20.dp))

        if (state.discovered.isNotEmpty()) {
            Text(
                "Найдено в сети",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            state.discovered.forEach { pc ->
                Button(
                    onClick = { onPair(pc.host, pc.port) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("${pc.name} · ${pc.host}:${pc.port}") }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "или вручную:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text("Адрес") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("Порт") },
                singleLine = true,
                modifier = Modifier.width(110.dp),
            )
        }
        Spacer(Modifier.height(16.dp))

        if (state.busy) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("Подтвердите на компьютере…", style = MaterialTheme.typography.bodyMedium)
        } else {
            Button(
                onClick = { port.toIntOrNull()?.let { onPair(host, it) } },
                enabled = host.isNotBlank() && port.toIntOrNull() != null,
            ) { Text("Связать") }
        }

        state.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.pairing != null) {
                OutlinedButton(onClick = onUnpair) { Text("Отвязать") }
            }
            TextButton(onClick = onClose) { Text("Закрыть") }
        }
    }
}

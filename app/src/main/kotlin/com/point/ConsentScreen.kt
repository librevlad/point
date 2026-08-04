package com.point

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Cloud-privacy consent (#10). Cloud actions (AI, Перевести, В Excel) upload the
 * user's object to an AI provider — nothing leaves the device until the user agrees here.
 * True to Point's no-menu style: a single contextual decision, shown the moment it matters
 * (the first cloud action), not buried in settings.
 *
 * #114: вопрос называет своё обещание сам — [title] и [confirm] приходят от действия. «Выложить
 * файл по ссылке?» и «Отправить в облако?» — разные решения, и человек соглашается с тем, что
 * написано на кнопке, а не с «облаком вообще».
 */
@Composable
fun ConsentScreen(
    onAllow: () -> Unit,
    onDecline: () -> Unit,
    /** Куда именно уедет объект — текст зависит от действия (#388). */
    destination: String = "",
    title: String = "",
    confirm: String = "",
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title.ifBlank { "Отправить в облако?" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = destination.ifBlank {
                "Объект уйдёт на сервер AI-провайдера и вернётся результатом. Ничего не " +
                    "отправляется без вашего согласия."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onAllow,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text(confirm.ifBlank { "Разрешить" }, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDecline) {
            Text("Не сейчас", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

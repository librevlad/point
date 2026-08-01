package com.point.core.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.flow.ActionReadiness
import com.point.core.flow.Readiness
import com.point.core.flow.actionReadiness
import com.point.core.flow.readingModeOf
import com.point.core.flow.readingModeLabel

/**
 * Готовность действий (#260, design v3 §6): полнота считается **по действию**, а не по числу
 * заполненных полей. Строка — «действие готово» либо «не хватает только X»; никакой формы из
 * девяти полей. Тап по неготовому раскрывает честное «почему»: чего не нашлось в прочитанном
 * и какие чтения спорят («или: …» из `<key>.alt`). Глубже — кандидаты с уликами на каждое
 * поле — придёт со срезом #261; выдумывать улики раньше времени эта карточка не имеет права.
 *
 * Секция видна только когда документ имеет отношение к действию (хоть одно поле прочитано):
 * пустой снимок не получает список «не хватает всего» — это был бы тот же опросник, только
 * с минусами.
 */
@Composable
internal fun ReadinessSection(metadata: Map<String, String>) {
    val rows = remember(metadata) { actionReadiness(metadata) }
    if (rows.isEmpty()) return
    // «Понять» уже спрашивали? От этого зависит честная формулировка отсутствия: до модели
    // трек искало только офлайн-правило, и «не нашлось» обязано звучать как «офлайн не нашлось».
    val understood = metadata["op"] == "understand"
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .padding(top = 12.dp)
            .widthIn(max = 340.dp)
            .animateContentSize(tween(220)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            rows.forEach { row -> key(row.schema.id) { ReadinessRow(row, understood, metadata) } }
        }
    }
}

@Composable
private fun ReadinessRow(row: ActionReadiness, understood: Boolean, metadata: Map<String, String>) {
    val ready = row.readiness is Readiness.Ready
    val present = when (val r = row.readiness) {
        is Readiness.Ready -> r.present
        is Readiness.Missing -> r.present
    }
    val missing = (row.readiness as? Readiness.Missing)?.missing.orEmpty()
    val disputed = present.filter { it.alternatives.isNotEmpty() }
    // Что именно раскрыто — переживает пересборку списка (rememberSaveable), но не притворяется
    // состоянием объекта: это чисто взгляд человека.
    var expanded by rememberSaveable(row.schema.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (missing.isNotEmpty()) it.clickable { expanded = !expanded } else it }
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (ready) "✓" else "•",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (ready) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = row.schema.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Готовое действие показывает своё ключевое значение — то, ради чего оно готово.
            // «Возможно» — предположение (#261): улик меньше двух независимых классов.
            // «с рукописи» — другой контракт доверия (#263): значение прочитано зрячей
            // моделью с пикселей, проверить его против слов страницы невозможно.
            if (ready) {
                present.firstOrNull { it.spec.critical }?.let { field ->
                    Spacer(Modifier.width(6.dp))
                    val origin = readingModeLabel(readingModeOf(metadata))?.let { " · $it" }.orEmpty()
                    val doubt = if (field.assumption) " · возможно" else ""
                    Text(
                        text = field.value + doubt + origin,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Спор виден без тапа (контракт FieldReading: готовность не прячет спор — человек
        // обязан видеть, что значение спорное, ДО того как начнёт отслеживать не тот номер).
        disputed.forEach { field ->
            Text(
                text = "${field.spec.label} — или: " + field.alternatives.joinToString(", "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 18.dp, top = 2.dp),
            )
        }
        if (!ready) {
            Text(
                text = "не хватает только: " + missing.joinToString(", ") { it.label },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, top = 2.dp),
            )
        }
        if (expanded) {
            missing.forEach { spec ->
                // Отклонённое проверкой — не «не нашлось» (ревью #261): чтение было, но
                // контрольная цифра не сошлась, и человек обязан видеть, ЧТО прочиталось.
                val blockedReadings = metadata[spec.key + com.point.core.flow.META_BLOCKED_SUFFIX]
                    ?.split("\n")?.filter { it.isNotBlank() }.orEmpty()
                Text(
                    // «Не нашлось» — не вердикт, а состояние поиска: до «Понять» искало только
                    // офлайн-правило, и оно слепо к чужим форматам (у идентификатора нет
                    // универсальной формы) — ложный тупик хуже честного «можно спросить модель».
                    text = when {
                        blockedReadings.isNotEmpty() ->
                            "${spec.label} — прочиталось «${blockedReadings.first()}», но контрольная цифра не сошлась"
                        understood -> "${spec.label} — в прочитанном не нашлось"
                        else -> "${spec.label} — офлайн не нашлось; «Понять» может найти"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp, top = 2.dp),
                )
            }
        }
    }
}

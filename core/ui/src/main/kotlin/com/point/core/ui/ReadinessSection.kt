package com.point.core.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
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
import com.point.core.flow.maskedForScreen
import com.point.core.flow.readingModeOf
import com.point.core.flow.readingModeLabel
import com.point.core.flow.runner
import com.point.core.model.Bubble

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
 *
 * **Строка = действие (#464).** Готовая строка запускается тапом — решение владельца, увидевшего
 * карточку живьём: «эти вещи некликабельны, по крайней мере были — надо пересмотреть подход». До
 * него кликабельным было только НЕготовое, то есть тап существовал ровно там, где делать нечего, а
 * галочка с глаголом и найденным значением обещала запуск и не давала его. Теперь карточка — список
 * действий над найденным, и запускает она те же пузыри, что список ниже: [bubbles] приходят из
 * реестра, [onBubble] — та же дорога исполнения, второй нет. Бизнес-логики тут по-прежнему ноль:
 * чем строка запускается (и запускается ли), решает чистая [runner] в `:core:flow`.
 *
 * [enabled] — принимает ли экран тапы вообще (не идёт действие, не ждут ввода). Выключенная
 * карточка шеврона не показывает: обещать запуск, которого сейчас не будет, — то же враньё,
 * только с другой стороны.
 */
@Composable
internal fun ReadinessSection(
    metadata: Map<String, String>,
    bubbles: List<Bubble> = emptyList(),
    enabled: Boolean = true,
    onBubble: (Bubble) -> Unit = {},
) {
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
            rows.forEach { row ->
                key(row.schema.id) {
                    ReadinessRow(
                        row = row,
                        understood = understood,
                        metadata = metadata,
                        runner = if (enabled) row.runner(bubbles) else null,
                        onRun = onBubble,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadinessRow(
    row: ActionReadiness,
    understood: Boolean,
    metadata: Map<String, String>,
    runner: Bubble?,
    onRun: (Bubble) -> Unit,
) {
    val ready = row.readiness is Readiness.Ready
    val present = when (val r = row.readiness) {
        is Readiness.Ready -> r.present
        is Readiness.Missing -> r.present
    }
    val missing = (row.readiness as? Readiness.Missing)?.missing.orEmpty()
    val disputed = present.filter { it.alternatives.isNotEmpty() }
    val hinted = present.mapNotNull { field -> field.hint?.let { field to it } }
    // Что именно раскрыто — переживает пересборку списка (rememberSaveable), но не притворяется
    // состоянием объекта: это чисто взгляд человека.
    var expanded by rememberSaveable(row.schema.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Две разные строки — два разных тапа, и ни один не делает вид, что он другой (#464):
            // готовое ДЕЛАЕТ то, что на нём написано; неготовое раскрывает, чего не хватает.
            // Готовое без реализации (сегодня — «Отследить отправление», «Передать показание»,
            // «Перевести по реквизитам», «Переслать квитанцию») остаётся справкой и шеврона не
            // носит: обещание кнопки без кнопки и было находкой владельца.
            .let {
                when {
                    runner != null -> it.clickable { onRun(runner) }
                    missing.isNotEmpty() -> it.clickable { expanded = !expanded }
                    else -> it
                }
            }
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
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
            val keyField = if (ready) present.firstOrNull { it.spec.critical } else null
            if (keyField != null) {
                Spacer(Modifier.width(6.dp))
                val origin = readingModeLabel(readingModeOf(metadata))?.let { " · $it" }.orEmpty()
                val doubt = if (keyField.assumption) " · возможно" else ""
                // Значение печатается ровно так, как его показывают человеку везде: номер
                // карты «Перевести по реквизитам» — хвостом (#240 — маска, мимо которой
                // однажды утёк номер, была на одном экране, а не у ключа факта).
                Text(
                    text = maskedForScreen(keyField.spec.key, keyField.value) + doubt + origin,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else if (runner != null) {
                Spacer(Modifier.weight(1f))
            }
            // Шеврон — единственный знак, которым строка говорит «я делаю это» (#464). Он тот же,
            // что у строк действий ниже (дизайн-система, [PortalRow]), но подсвечен цветом
            // действия: в одной карточке стоят живые строки и справочные, и различать их обязано
            // что-то, кроме надежды человека.
            if (runner != null) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        // Ведущие нули барабана (#262): дословное значение остаётся на своём месте, а рядом —
        // то, что человек, скорее всего, передаст. Строка называет ОБА числа и говорит, какое
        // со страницы: карточка, показавшая одно «1154», молча решила бы за человека, сколько
        // разрядов значащие, — а это знает поставщик услуги, не Point. Строка идёт за
        // ПРОЧИТАННЫМ полем, а не за готовностью действия — прятать прочитанное до готовности
        // незачем; сегодня разницы не видно (показание — единственное критическое поле своей
        // схемы, и раз оно прочитано, действие уже готово), но правило именно такое.
        // Названная граница: «обычно передают» — фраза единственного поля, у которого сегодня
        // есть подсказка ([fieldHint]); второму придётся решить, годится ли она ему.
        hinted.forEach { (field, hint) ->
            Text(
                text = "${field.spec.label} — со страницы «${field.value}», обычно передают «$hint»",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 18.dp, top = 2.dp),
            )
        }
        // Спор виден без тапа (контракт FieldReading: готовность не прячет спор — человек
        // обязан видеть, что значение спорное, ДО того как начнёт отслеживать не тот номер).
        disputed.forEach { field ->
            Text(
                text = "${field.spec.label} — или: " +
                    field.alternatives.joinToString(", ") { maskedForScreen(field.spec.key, it) },
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

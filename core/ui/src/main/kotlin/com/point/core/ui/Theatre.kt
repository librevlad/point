package com.point.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import com.point.core.ui.theme.PointText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Микро-театр обогащения (#209): найденное значение вылетает из вещи и приземляется в свою
 * строку. Не заставка перед продуктом, а сам продукт, ставший видимым, — решение владельца
 * 01.09.2026.
 *
 * Правило первенства одно: театр никогда не ярче главного действия и никогда не двигает
 * вёрстку. Он рисуется поверх готового экрана, список нажимаем с первого кадра, касание
 * обрывает полёт мгновенно (D4), а само значение всё это время живёт текстом в своей строке —
 * улетевшее и не приземлившееся было бы недоступно ни скринридеру, ни пальцу.
 */
enum class TheatreStage {
    /** Первые встречи: полёт целиком. */
    FULL,

    /** Человек уже видел, как это работает: полёт короче и тише. */
    QUIET,

    /** Привычка: полёта нет, остаётся приземление значения в строке. */
    LANDING,
}

/** Сколько полётов уже сыграно — столько раз человек видел театр (D7). */
fun theatreStageFor(played: Int): TheatreStage = when {
    played < THEATRE_FULL_SHOWS -> TheatreStage.FULL
    played < THEATRE_QUIET_SHOWS -> TheatreStage.QUIET
    else -> TheatreStage.LANDING
}

internal const val THEATRE_FULL_SHOWS = 10
internal const val THEATRE_QUIET_SHOWS = 30

/** Значение, которому есть куда приземлиться: заголовок своего раздела действий. */
data class TheatreValue(val key: String, val text: String, val leads: Boolean)

/**
 * Что взлетает, когда граф дописал найденное (D3, D8): одно значение, а не залп, — то, что
 * открывает главное действие. Значения, которые уже стояли на экране, не взлетают: знание,
 * пришедшее с вещью, — не находка этого раза.
 */
fun nextFlight(seen: Set<String>, values: List<TheatreValue>): TheatreValue? {
    val fresh = values.filter { it.key !in seen }
    return fresh.firstOrNull { it.leads } ?: fresh.firstOrNull()
}

/**
 * Волна кольца (D6): Point смотрел, а нового на экране не прибавилось. Сорвавшийся поиск —
 * то же «не нашлось» для глаза; знание об этом хранит граф, а не театр.
 */
fun lookedAndFoundNothing(wasLooking: Boolean, looking: Boolean, knownAtOpen: Int, knownNow: Int): Boolean =
    wasLooking && !looking && knownNow <= knownAtOpen

@Stable
internal class TheatreState {
    /** Значения, которые уже стояли на экране; пришедшее вместе с вещью сюда попадает сразу. */
    var seen: Set<String> = emptySet()

    /** Сколько знания было на экране при открытии — по нему видно, нашлось ли что-то ещё. */
    var knownAtOpen = 0
        private set
    private var opened = false
    var flying by mutableStateOf<TheatreValue?>(null)
        private set

    /** Строка, в которую значение приземлилось последним; она коротко подсвечивается. */
    var landed by mutableStateOf<String?>(null)
        private set
    var landings by mutableStateOf(0)
        private set
    var wasLooking = false
    var hero by mutableStateOf<Rect?>(null)
    var origin by mutableStateOf(Offset.Zero)
    val targets = mutableStateMapOf<String, Offset>()
    val progress = Animatable(0f)
    val wave = Animatable(0f)
    private var flight: Job? = null

    /**
     * Открытие экрана: всё, что уже известно, — не находка этого раза. Повторно открытая вещь
     * приходит со знанием в графе, и театра на ней нет.
     */
    fun openOnce(keys: Set<String>, known: Int) {
        if (opened) return
        opened = true
        seen = keys
        knownAtOpen = known
    }

    fun hidden(key: String): Boolean = flying?.key == key

    fun lit(key: String): Boolean = landed == key

    fun play(value: TheatreValue, stage: TheatreStage, motion: Boolean, scope: CoroutineScope, onPlayed: () -> Unit) {
        if (flight?.isActive == true) {
            land(value.key)
            return
        }
        if (stage == TheatreStage.LANDING || !motion) {
            land(value.key)
            return
        }
        flight = scope.launch {
            val to = withTimeoutOrNull(MotionTokens.FLIGHT_MS.toLong()) {
                snapshotFlow { targets[value.key] }.filterNotNull().first()
            }
            if (hero == null || to == null) {
                land(value.key)
                return@launch
            }
            flying = value
            try {
                progress.snapTo(0f)
                val ms = if (stage == TheatreStage.QUIET) MotionTokens.QUIET_FLIGHT_MS else MotionTokens.FLIGHT_MS
                progress.animateTo(1f, tween(ms, easing = FastOutSlowInEasing))
                onPlayed()
            } finally {
                flying = null
                land(value.key)
            }
        }
    }

    /** Касание обрывает полёт сразу: значение оказывается на месте. */
    fun interrupt() {
        flight?.cancel()
    }

    private fun land(key: String) {
        landed = key
        landings++
    }
}

@Composable
internal fun rememberTheatre(id: String): TheatreState = remember(id) { TheatreState() }

/** Любое касание экрана обрывает полёт; само касание при этом доходит до того, во что целили. */
internal fun Modifier.theatreStops(theatre: TheatreState): Modifier = pointerInput(theatre) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            theatre.interrupt()
        }
    }
}

internal fun Modifier.theatreOrigin(theatre: TheatreState): Modifier =
    onGloballyPositioned { theatre.origin = it.positionInRoot() }

internal fun Modifier.theatreHero(theatre: TheatreState?): Modifier =
    if (theatre == null) this else onGloballyPositioned { theatre.hero = it.boundsInRoot() }

/** Место, куда значение приземляется: начало его текста в заголовке раздела. */
internal fun Modifier.theatreTarget(theatre: TheatreState?, key: String): Modifier =
    if (theatre == null) {
        this
    } else {
        onGloballyPositioned {
            val at = it.positionInRoot()
            theatre.targets[key] = Offset(at.x, at.y + it.size.height / 2f)
        }
    }

/** Слой театра поверх экрана: одно значение в воздухе или одна волна кольца. */
@Composable
internal fun TheatreLayer(theatre: TheatreState, stage: TheatreStage) {
    if (theatre.wave.isRunning) {
        val hero = theatre.hero
        if (hero != null) {
            val t = theatre.wave.value
            val alpha = if (stage == TheatreStage.QUIET) 0.25f else 0.4f
            Canvas(Modifier.fillMaxSize()) {
                val c = hero.center - theatre.origin
                val r = hero.minDimension / 2f
                drawCircle(
                    color = PointPalette.violet,
                    radius = r * (1f + 0.35f * t),
                    center = c,
                    alpha = alpha * (1f - t),
                    style = Stroke(width = r * 0.04f),
                )
            }
        }
    }

    val value = theatre.flying ?: return
    val from = theatre.hero?.center ?: return
    val quiet = stage == TheatreStage.QUIET
    Text(
        text = value.text,
        style = PointText.rowTitle.copy(fontSize = PointText.rowTitle.fontSize * 1.1f, color = PointPalette.text),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            // Значение уже стоит текстом в своей строке: летящая копия — только картинка.
            .clearAndSetSemantics { }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val p = theatre.progress.value
                    val to = theatre.targets[value.key] ?: from
                    val at = lerp(from, to, p) - theatre.origin
                    // Взлетает из середины вещи, а садится началом строки — как стоит в заголовке.
                    val x = at.x - placeable.width * (1f - p) / 2f
                    val y = at.y - placeable.height / 2f
                    placeable.place(x.roundToInt(), y.roundToInt())
                }
            }
            .graphicsLayer {
                val p = theatre.progress.value
                val scale = 0.82f + 0.18f * p
                scaleX = scale
                scaleY = scale
                alpha = (if (quiet) 0.7f else 0.95f) * (0.4f + 0.6f * p)
            },
    )
}

/** Одна волна кольца и тишина: смотрели, не нашли (D6). */
internal suspend fun TheatreState.waveOnce() {
    wave.snapTo(0f)
    wave.animateTo(1f, tween(MotionTokens.WAVE_MS))
    wave.snapTo(0f)
}

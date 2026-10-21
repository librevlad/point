package com.point

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.point.core.model.tr
import com.point.core.ui.GlassButton
import com.point.core.ui.GlassGroup
import com.point.core.ui.LightButton
import com.point.core.ui.PointPalette
import com.point.core.ui.theme.PointText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Point сам предлагает разрешить уведомления (#1449).
 *
 * Прежде разрешение просилось только строкой в «Моих устройствах», и человек, не зная о ней,
 * решал, что стук компьютера не работает: на Android 13+ без разрешения не показывается ни
 * одно уведомление. Теперь Point спрашивает сам — один раз, в тот момент, когда стучать
 * становится кому: в круге появился компьютер или объект впервые ушёл на него.
 *
 * Opt-in остаётся: Point объясняет, зачем, и человек решает. Системный запрос Android даёт
 * задать дважды, после второго «нет» молчит навсегда — поэтому сначала спрашивает Point своими
 * словами, а системное окно открывается только на «Разрешить». Отказ здесь не тратит ни одной
 * попытки, а строка в «Моих устройствах» остаётся на потом.
 */

private const val KNOCK_PREFS = "knock"

private const val KEY_OFFERED = "offered"

/** Предлагали ли уже — ответ человека, каким бы он ни был, второй раз не переспрашивается. */
fun knockOffered(context: Context): Boolean =
    context.getSharedPreferences(KNOCK_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OFFERED, false)

fun rememberKnockOffered(context: Context) {
    context.getSharedPreferences(KNOCK_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_OFFERED, true)
        .apply()
}

/**
 * Предложить ли разрешение сейчас.
 *
 * До Android 13 разрешения нет вовсе; без компьютера в круге стучать некому; уже разрешено
 * или уже спрашивали — говорить не о чем.
 */
fun knockOfferVisible(sdkInt: Int, pcInCircle: Boolean, knockOff: Boolean, offered: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && pcInCircle && knockOff && !offered

/**
 * Предложение разрешить уведомления — поверх любого экрана Point, внизу.
 *
 * Держит свой запрос разрешения: экран «с чего начать» и экран объекта рисуются разными
 * дверями, а спросить надо из обеих. [blocked] — экран сейчас занят своим вопросом (вход,
 * согласие, работа идёт), и второй вопрос поверх него не задаётся.
 */
@Composable
fun KnockOffer(
    pcInCircle: Boolean,
    modifier: Modifier = Modifier,
    blocked: Boolean = false,
    onGranted: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Пока не прочитано, спрашивали ли уже, — молчим: прочитать — это I/O, и первому экрану
    // оно не мешает.
    var offered by remember { mutableStateOf(true) }
    var knockOff by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        knockOff = !granted

        // Разрешение получено — адрес доставки уходит на сервер сейчас, а не со следующего
        // запуска (#1118).
        if (granted) {
            (context.applicationContext as? PointApplication)?.tellWhereToKnock()
            onGranted()
        }
    }
    LaunchedEffect(pcInCircle) {
        if (!pcInCircle) return@LaunchedEffect
        val (wasOffered, off) = withContext(Dispatchers.IO) { knockOffered(context) to notificationsOff(context) }
        knockOff = off
        offered = wasOffered

        // Компьютер в круге — адрес для стука уходит на сервер и сейчас. Адрес, выданный до
        // входа в аккаунт, иначе ждал бы следующего холодного запуска Point (#1449).
        (context.applicationContext as? PointApplication)?.tellWhereToKnock()
    }
    if (blocked || !knockOfferVisible(Build.VERSION.SDK_INT, pcInCircle, knockOff, offered)) return

    val answer = { allow: Boolean ->
        offered = true
        rememberKnockOffered(context)
        if (allow) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    KnockOfferCard(onAllow = { answer(true) }, onLater = { answer(false) }, modifier = modifier)
}

private fun notificationsOff(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED

/** Просьба словами человека: что он получит, а не как это устроено. */
@Composable
fun KnockOfferCard(onAllow: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    GlassGroup(modifier) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(KNOCK_OFFER_TITLE, style = PointText.rowTitle.copy(color = PointPalette.text))
            Text(KNOCK_OFFER_WHY, style = PointText.note.copy(color = PointPalette.muted))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassButton(KNOCK_OFFER_LATER, onClick = onLater, modifier = Modifier.weight(1f))
            LightButton(KNOCK_OFFER_ALLOW, onClick = onAllow, modifier = Modifier.weight(1f))
        }
    }
}

private val KNOCK_OFFER_TITLE: String get() = tr("Сообщать о просьбах компьютера?")
private val KNOCK_OFFER_WHY: String get() =
    tr("Компьютер позовёт вас, когда ему понадобится телефон. Без уведомлений просьба ждёт, пока вы откроете Point сами")
private val KNOCK_OFFER_ALLOW: String get() = tr("Разрешить")
private val KNOCK_OFFER_LATER: String get() = tr("Не сейчас")

@Preview(widthDp = 360, backgroundColor = 0xFF101418, showBackground = true)
@Composable
private fun KnockOfferPreview() {
    KnockOfferCard(onAllow = {}, onLater = {}, modifier = Modifier.padding(16.dp))
}

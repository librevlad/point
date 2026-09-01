package com.point.desktop.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * Клавиши окна: корень слышит их сам, но не отбирает у того, во что человек печатает (#1025).
 *
 * Без сфокусированного узла Compose не доставляет клавиатуру вовсе — поэтому корень окна
 * берёт фокус сам, и Esc с хоткеями слышны без единого клика мышью (живой прогон
 * 2026-08-09). Но просить фокус один раз при старте нельзя: окно, показанное без фокуса ОС,
 * съедало единственный запрос впустую, и клавиши молчали, пока фокус не добывали Tab'ом
 * (живая Windows, владелец 20.08.2026).
 *
 * И нельзя просить его на каждом возврате в окно: человек, ушедший в браузер за ключом и
 * вернувшийся в поле настроек, обнаруживал, что курсор из поля выдернули и буквы идут мимо.
 * Поэтому фокус просится только тогда, когда его внутри окна не держит никто — ни сам
 * корень, ни поле ввода.
 */
@Composable
fun Modifier.windowKeys(onKey: (KeyEvent) -> Boolean): Modifier {
    val root = remember { FocusRequester() }

    // Держит ли фокус хоть кто-то внутри окна: `hasFocus` — про всё поддерево, а не про
    // сам корень, поэтому сфокусированное поле ввода тоже считается «держит».
    var held by remember { mutableStateOf(false) }

    val windowFocused = LocalWindowInfo.current.isWindowFocused

    LaunchedEffect(windowFocused, held) {
        if (windowFocused && !held) root.requestFocus()
    }

    return this
        .onFocusChanged { held = it.hasFocus }
        .focusRequester(root)
        .focusable()

        // Клик мышью не должен хоронить клавиши (#1372, живой прогон 01.09.2026: после
        // клика по списку Esc и Ctrl+V умирали до смены окна ОС — кликнутая строка
        // исчезала вместе с фокусом, и подсказка «нажмите Ctrl+V» ломалась первым же
        // кликом). Жест дожидается конца в финальной фазе — детям он не мешает, — и
        // если после него фокус внутри окна никто не держит, корень берёт его назад.
        .pointerInput(root) {
            awaitEachGesture {
                awaitFirstDown(pass = PointerEventPass.Final)
                waitForUpOrCancellation(pass = PointerEventPass.Final)
                if (!held) root.requestFocus()
            }
        }
        .onPreviewKeyEvent(onKey)
}

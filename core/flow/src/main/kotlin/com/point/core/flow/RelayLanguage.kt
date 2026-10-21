package com.point.core.flow

import com.point.core.model.Lang
import java.net.HttpURLConnection

/**
 * Сервер Point отвечает на языке человека (#1473): отказ, который он пишет словами, Point
 * показывает как есть. Язык сервер берёт из `Accept-Language` — тот же, на котором говорит
 * само приложение, а не язык Java по умолчанию, который может с ним разойтись.
 */
fun HttpURLConnection.speakPointLanguage() {
    setRequestProperty("Accept-Language", Lang.current().tag)
}

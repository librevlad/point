package com.point.source

import android.app.StatusBarManager
import android.content.Context
import android.os.Build

/**
 * Плитка Point в шторке (#246) — и то, как о ней узнаёт человек (#456).
 *
 * Плитка была построена и объявлена в манифесте, но предложить её себе Point не умел: `Android` не
 * ставит плитку сам, а редактор шторки открывают единицы. Значит четыре источника из пяти — камера,
 * голос, буфер, место — были недостижимы никому, кто туда не полез.
 *
 * Здесь лежит вся логика предложения, кроме самого системного диалога: правило «показывать ли», имя
 * итога и память о том, что плитка уже стоит. Всё, кроме диалога, — чистые функции, поэтому
 * судится JVM-тестом, а не руками на телефоне.
 */

/** Где живёт память о плитке. Своё хранилище, а не общее: вопрос ровно один и он про шторку. */
private const val SHADE_PREFS = "shade"

/** Плитка Point стоит в шторке — насколько Point об этом знает. */
private const val KEY_TILE_ADDED = "tile_added"

/**
 * Стоит ли плитка в шторке, по тому, что Point видел своими глазами.
 *
 * Спросить систему нельзя: API «а стоит ли моя плитка» не существует. Зато плитка сама знает о себе
 * — [PointTileService] пишет сюда, когда её добавили и когда шторка начала её слушать, и стирает,
 * когда её убрали. Поэтому «не знаем» и «не стоит» здесь одно и то же, и это честно: пока Point не
 * видел ни одного признака, предложить плитку — не навязчивость, а единственный способ о ней узнать.
 */
fun shadeTileKnown(context: Context): Boolean =
    context.getSharedPreferences(SHADE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TILE_ADDED, false)

/** Запомнить, что плитка есть (или что её больше нет). */
fun rememberShadeTile(context: Context, added: Boolean) {
    context.getSharedPreferences(SHADE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_TILE_ADDED, added)
        .apply()
}

/**
 * Показывать ли на экране выбора строку «Поставить плитку в шторку».
 *
 * Двe причины не показывать, и обе — про честность, а не про вкус. До Android 13 просить систему
 * не о чем: `requestAddTileService` появился в 13, и строка вела бы в никуда. А если Point уже
 * видел свою плитку живой, предложение поставить её — вранье в лицо.
 */
fun tileOfferVisible(sdkInt: Int, known: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !known

/** Чем кончилась просьба поставить плитку — словами, а не кодом системы. */
enum class TileAddOutcome {
    /** Человек согласился в системном диалоге. */
    ADDED,

    /** Плитка уже стояла — диалога человек даже не увидел. */
    ALREADY,

    /** Человек отказался. Это ответ, а не ошибка: повторять вопрос не надо. */
    DECLINED,

    /** Система не дала спросить (не тот пользователь, шторки нет, просьба уже в работе). */
    FAILED,
}

/** Знает ли Point после такого итога, что плитка на месте. */
val TileAddOutcome.tilePresent: Boolean
    get() = this == TileAddOutcome.ADDED || this == TileAddOutcome.ALREADY

fun tileAddOutcome(result: Int): TileAddOutcome = when (result) {
    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> TileAddOutcome.ADDED
    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> TileAddOutcome.ALREADY
    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> TileAddOutcome.DECLINED
    else -> TileAddOutcome.FAILED
}

/**
 * Что сказать человеку об итоге. `null` — сказать нечего.
 *
 * Молчат ровно два случая, и оба потому, что человек и так всё видел: он сам только что нажал
 * «Добавить» в системном диалоге (строка после этого пропадает — это и есть ответ) или сам же
 * отказался. Говорят те два, где человек не увидел ничего: диалог не появился вовсе.
 */
fun tileAddMessage(outcome: TileAddOutcome): String? = when (outcome) {
    TileAddOutcome.ADDED, TileAddOutcome.DECLINED -> null
    TileAddOutcome.ALREADY -> "Плитка Point уже в шторке"
    TileAddOutcome.FAILED -> "Не получилось предложить плитку — её можно добавить в редакторе шторки"
}

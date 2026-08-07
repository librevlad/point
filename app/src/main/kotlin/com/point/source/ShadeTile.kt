package com.point.source

import android.app.StatusBarManager
import android.content.Context
import android.os.Build

private const val SHADE_PREFS = "shade"

private const val KEY_TILE_ADDED = "tile_added"

fun shadeTileKnown(context: Context): Boolean =
    context.getSharedPreferences(SHADE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TILE_ADDED, false)

fun rememberShadeTile(context: Context, added: Boolean) {
    context.getSharedPreferences(SHADE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_TILE_ADDED, added)
        .apply()
}

fun tileOfferVisible(sdkInt: Int, known: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !known

enum class TileAddOutcome {

    ADDED,

    ALREADY,

    DECLINED,

    FAILED,
}

val TileAddOutcome.tilePresent: Boolean
    get() = this == TileAddOutcome.ADDED || this == TileAddOutcome.ALREADY

fun tileAddOutcome(result: Int): TileAddOutcome = when (result) {
    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> TileAddOutcome.ADDED
    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> TileAddOutcome.ALREADY
    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> TileAddOutcome.DECLINED
    else -> TileAddOutcome.FAILED
}

fun tileAddMessage(outcome: TileAddOutcome): String? = when (outcome) {
    TileAddOutcome.ADDED, TileAddOutcome.DECLINED -> null
    TileAddOutcome.ALREADY -> "Плитка Point уже в шторке"
    TileAddOutcome.FAILED -> "Не получилось предложить плитку — её можно добавить в редакторе шторки"
}

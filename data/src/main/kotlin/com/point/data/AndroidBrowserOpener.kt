package com.point.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.point.core.flow.BrowserOpener
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Открыть страницу входа системным браузером (#472).
 *
 * Своего окна для чужих страниц у Point нет и не будет — он не браузер (продуктовый фильтр). Вход
 * от этого не страдает: страница подтверждения чужая по построению, и показывать её обязан тот, кому
 * человек уже доверяет.
 *
 * `NEW_TASK` нужен потому, что открывать умеет и не-Activity: пропуск может протухнуть в фоне, и
 * дверь входа поднимется не из экрана.
 */
class AndroidBrowserOpener @Inject constructor(
    @ApplicationContext private val context: Context,
) : BrowserOpener {

    override fun open(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

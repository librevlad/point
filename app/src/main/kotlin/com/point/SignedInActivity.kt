package com.point

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Дверь из браузера обратно в Point после входа (#561).
 *
 * Раньше человек, подтвердивший вход у Google, читал «можно закрывать страницу» и возвращался в
 * приложение сам — руками, через список задач. Это и был лишний шаг: вход начат на этом же
 * телефоне, браузер открыл сам Point, и вернуть человека — работа приложения, а не его.
 *
 * Активити ничего не рисует и ничего не решает: она **поднимает задачу Point на передний план** и
 * уходит. Дальше срабатывает то, что уже есть, — `onResume` дожимает начатый вход
 * ([FlowViewModel.resumeSignIn]). Держать здесь свой опрос значило бы завести вторую половину
 * входа, которая расходилась бы с первой.
 *
 * `singleTask` с обычной привязкой к приложению — то, чем это делается: если задача Point жива,
 * система переносит её вперёд и кладёт эту активити наверх; `finish()` открывает под ней ровно тот
 * экран, с которого человек ушёл в браузер. Если Point к этому времени закрыли совсем, задачи нет —
 * тогда открывается «Недавнее», и вход дожимается там.
 */
class SignedInActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // `isTaskRoot` — единственный признак того, что под нами ничего нет: Point закрыли, пока
        // человек был в браузере. Открывать «Недавнее» всегда было бы хуже — это выбрасывало бы из
        // объекта, ради которого вход и понадобился.
        if (isTaskRoot) {
            startActivity(
                Intent(this, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }
}

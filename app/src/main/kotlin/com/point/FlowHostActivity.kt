package com.point

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.point.core.ui.theme.PointTheme

/**
 * Общий хост флоу для всех дверей Point (#249).
 *
 * Дверей стало больше одной, и каждая приносила с собой копию одного и того же экрана: список из
 * четырёх десятков колбэков [PointHost] лежал и в `ShareActivity`, и в `ProcessTextActivity` —
 * вместе с обязательной уборкой scratch и перехватом «назад». Третья дверь сделала бы три копии,
 * и разошлись бы они молча: копипаста не падает, она тихо стареет.
 *
 * Здесь живёт всё, что у дверей общее. Дверь отличается ровно одним — [accept]: как разобрать
 * свой intent и что отдать во флоу.
 */
abstract class FlowHostActivity : ComponentActivity() {

    protected val viewModel: FlowViewModel by viewModels()

    /** Разобрать свой intent и отдать объект во флоу. Зовётся при создании и на новом intent. */
    protected abstract fun accept(intent: Intent)

    /**
     * Поднимать ли работу, прерванную смертью процесса (#7). Только у двери «Поделиться»: иконка
     * в лаунчере обязана открывать домашний экран, а не чужую недоделанную работу.
     */
    protected open val restoresJourney: Boolean get() = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) accept(intent)
        if (restoresJourney) viewModel.restoreJourney()

        onBackPressedDispatcher.addCallback(this) {
            if (!viewModel.onBack()) {
                isEnabled = false
                this@FlowHostActivity.onBackPressedDispatcher.onBackPressed()
            }
        }

        setContent {
            PointTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val state by viewModel.ui.collectAsStateWithLifecycle()
                    PointFlow(
                        state = state,
                        viewModel = viewModel,
                        // Дверь «Поделиться»: «откуда пришли» — чужое приложение, значит выход с
                        // экрана-сообщения ведёт наружу, ровно как системный «назад» (#114).
                        onLeave = { onBackPressedDispatcher.onBackPressed() },
                        // ...и называется он тем, что делает (#531). «← Недавнее» здесь было
                        // обещанием, которого дверь не выполняет: тап возвращал в галерею.
                        leaveLabel = LEAVE_BACK,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTop: второй объект, прилетевший в живую активити, обязан начать новую работу —
        // без этого intent доставляется и молча теряется.
        accept(intent)
    }

    override fun onDestroy() {
        if (isFinishing) viewModel.endFlow() // обязательная уборка scratch
        super.onDestroy()
    }
}

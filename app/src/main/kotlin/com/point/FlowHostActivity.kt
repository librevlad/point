package com.point

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Toast
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

abstract class FlowHostActivity : ComponentActivity() {

    protected val viewModel: FlowViewModel by viewModels()

    protected abstract fun accept(intent: Intent)

    protected open val restoresJourney: Boolean get() = false

    /**
     * Пустое выделение отвечает словом, а не молчанием (#1096, решение владельца 20.08.2026).
     *
     * Человек нажал пункт меню — ответ прошен, поэтому короткий тост уместен. Объект не
     * заводится, экран не поднимается и предыдущий объект молча не восстанавливается:
     * onCreate после отказа не идёт дальше.
     */
    protected fun refuseEmptySelection() {
        Toast.makeText(this, EMPTY_SELECTION_WORDS, Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * Пропажа и возврат сети видны на открытом экране, без перезахода в объект (#758).
     *
     * Слушаем, пока экран перед человеком: за окном приложения состояние сети всё равно
     * спросят заново при следующей сборке списка.
     */
    private val networkWatch = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) = viewModel.networkChanged()

        override fun onLost(network: Network) = viewModel.networkChanged()

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            viewModel.networkChanged()
    }

    override fun onStart() {
        super.onStart()
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkWatch)
        }
    }

    override fun onStop() {
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkWatch) }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) accept(intent)
        if (isFinishing) return
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

                        onLeave = { onBackPressedDispatcher.onBackPressed() },

                        leaveLabel = LEAVE_BACK,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.returnedToPoint()
        viewModel.resumeSignIn()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        accept(intent)
    }

    override fun onDestroy() {
        if (isFinishing) viewModel.endFlow()
        super.onDestroy()
    }
}

/** Слово на пустое выделение (#1096): ответ прошен нажатием пункта меню. */
internal const val EMPTY_SELECTION_WORDS = "Выделение пустое — выделите текст"

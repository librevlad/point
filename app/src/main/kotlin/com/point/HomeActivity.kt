package com.point

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
import dagger.hilt.android.AndroidEntryPoint

/**
 * Point's launcher home. Shows recent objects (History); tapping one re-opens it
 * into the flow. While a flow is active it hosts the same [PointHost] as Share.
 */
@AndroidEntryPoint
class HomeActivity : ComponentActivity() {

    private val viewModel: FlowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.loadRecent()

        onBackPressedDispatcher.addCallback(this) {
            when {
                viewModel.onBack() -> Unit
                viewModel.hasFlow() -> { viewModel.endFlow(); viewModel.loadRecent() }
                else -> {
                    isEnabled = false
                    this@HomeActivity.onBackPressedDispatcher.onBackPressed()
                }
            }
        }

        setContent {
            PointTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state by viewModel.ui.collectAsStateWithLifecycle()
                    if (state.frame == null && !state.loading) {
                        val recent by viewModel.recent.collectAsStateWithLifecycle()
                        HomeScreen(recent = recent, onOpen = viewModel::openFromHistory)
                    } else {
                        PointHost(
                            state = state,
                            onBubble = viewModel::onBubble,
                            onSubmitInput = viewModel::submitAmendment,
                            onCancelInput = viewModel::cancelInput,
                            onApplyFavorite = viewModel::applyFavorite,
                            onSaveChain = viewModel::saveCurrentChain,
                            onItem = viewModel::onItem,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!viewModel.hasFlow()) viewModel.loadRecent()
    }
}

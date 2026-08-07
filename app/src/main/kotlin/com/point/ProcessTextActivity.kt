package com.point

import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ProcessTextActivity : FlowHostActivity() {

    override fun accept(intent: Intent) {

        val text = (
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)
            )?.toString().orEmpty()
        if (text.isBlank()) {
            finish()
            return
        }

        viewModel.onSharedText(text)
    }
}

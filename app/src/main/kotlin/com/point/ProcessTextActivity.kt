package com.point

import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ProcessTextActivity : FlowHostActivity() {

    override fun accept(intent: Intent) {

        // «Есть ли здесь текст» решает то же правило, что и на двери шаринга (#1096):
        // пробелы объектом не становятся, разметка выделения — становится.
        val body = bodyOf(
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY),
        )
        if (body == null) {
            refuseEmptySelection()
            return
        }

        viewModel.onSharedText(body.text)
    }
}

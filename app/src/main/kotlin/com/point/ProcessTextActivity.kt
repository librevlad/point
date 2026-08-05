package com.point

import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint

/**
 * «Правый клик по тексту»: Point зарегистрирован на ACTION_PROCESS_TEXT, поэтому появляется в
 * панели выделения любого приложения. Выделенное входит во флоу тем же путём, что объект из
 * «Поделиться» — через `onShared(fileUri, "text/plain")`; ни разрешений, ни API выше 23 не нужно.
 *
 * Экран и уборка — общие, в [FlowHostActivity]; здесь только разбор своего intent.
 */
@AndroidEntryPoint
class ProcessTextActivity : FlowHostActivity() {

    override fun accept(intent: Intent) {
        // EXTRA_PROCESS_TEXT — редактируемое выделение; READONLY-вариант остаётся запасным.
        val text = (
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)
            )?.toString().orEmpty()
        if (text.isBlank()) {
            finish()
            return
        }
        // Выделенное называется своими первыми словами (#533) — то же имя, что у текста из Share.
        viewModel.onSharedText(text)
    }
}

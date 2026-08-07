package com.point.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.point.core.flow.Clipboard
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class AndroidClipboard @Inject constructor(
    @ApplicationContext private val context: Context,
) : Clipboard {

    override suspend fun copy(text: String, label: String) = withContext(Dispatchers.Main) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: error("Буфер обмена недоступен")
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

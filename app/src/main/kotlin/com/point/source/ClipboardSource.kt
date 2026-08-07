package com.point.source

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import javax.inject.Inject

class ClipboardSource @Inject constructor(

    private val sharedTexts: com.point.core.flow.SharedTexts,
) : ObjectSource {

    override val id = "clipboard"
    override val label = "Буфер обмена"
    override val icon = "copy"

    override fun isAvailable(context: Context) =
        context.getSystemService(Context.CLIPBOARD_SERVICE) is ClipboardManager

    override suspend fun request(context: Context): Intent? = null

    override suspend fun read(context: Context, data: Intent?): Produced? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val item = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val uri = item?.uri
        val produced = clipToProduced(
            text = item?.text?.toString(),
            uri = uri?.toString(),
            mime = uri?.let { context.contentResolver.getType(it) },

            textFile = { text -> java.io.File(sharedTexts.create(text)).toURI().toString() },
        )

        if (produced == null) Toast.makeText(context, "В буфере пусто", Toast.LENGTH_SHORT).show()
        return produced
    }
}

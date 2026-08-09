package com.point.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.point.core.flow.UrlOpener
import com.point.core.flow.noAppFor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AndroidUrlOpener @Inject constructor(
    @ApplicationContext private val context: Context,
) : UrlOpener {

    override suspend fun open(url: String) {
        val uri = Uri.parse(url)

        // Намерение на схему (#678/#679): один ACTION_VIEW на всё терял смысл —
        // «Позвонить» открывало пустой диалер вместо набора с номером.
        val action = when (uri.scheme?.lowercase()) {
            "tel" -> Intent.ACTION_DIAL
            "smsto", "sms", "mailto" -> Intent.ACTION_SENDTO
            else -> Intent.ACTION_VIEW
        }
        val intent = Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {

            // Человеческий отказ вместо «No Activity found to handle Intent {…}» (#675).
            error(noAppFor(uri.scheme))
        }
    }
}

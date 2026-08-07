package com.point.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.point.core.flow.UrlOpener
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AndroidUrlOpener @Inject constructor(
    @ApplicationContext private val context: Context,
) : UrlOpener {

    override suspend fun open(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

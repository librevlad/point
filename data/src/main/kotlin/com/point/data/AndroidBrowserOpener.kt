package com.point.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.point.core.flow.BrowserOpener
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AndroidBrowserOpener @Inject constructor(
    @ApplicationContext private val context: Context,
) : BrowserOpener {

    override fun open(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

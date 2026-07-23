package com.point.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.point.core.flow.AppLauncher
import com.point.core.flow.AppTarget
import com.point.core.model.PointObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * The installed apps that can open an object, from `PackageManager.queryIntentActivities`, and a
 * launch of the chosen one (#66). The object is handed over as a [FileProvider] content Uri with a
 * read grant — the same authority Share/View use. Needs the `<queries>` block in the manifest so
 * Android 11+ package visibility returns results. Point itself is filtered out.
 */
class AndroidAppLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppLauncher {

    override suspend fun handlers(obj: PointObject): List<AppTarget> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.queryIntentActivities(viewIntent(obj), 0)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName } // not Point
            .map {
                AppTarget(
                    label = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                    activity = it.activityInfo.name,
                )
            }
            .distinctBy { it.packageName } // one entry per app
            .toList()
    }

    override suspend fun launch(target: AppTarget, obj: PointObject) {
        withContext(Dispatchers.IO) {
            val intent = viewIntent(obj).apply {
                setClassName(target.packageName, target.activity)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private fun viewIntent(obj: PointObject): Intent {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, File(obj.uri.value))
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, obj.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

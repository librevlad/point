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

class AndroidAppLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppLauncher {

    override suspend fun handlers(obj: PointObject): List<AppTarget> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.queryIntentActivities(viewIntent(obj), 0)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                AppTarget(
                    label = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                    activity = it.activityInfo.name,
                )
            }
            .distinctBy { it.packageName }
            .toList()
    }

    /**
     * Спрашиваем систему, кто объявил себя умеющим номер: звонилки, SMS-приложения,
     * определители. Своего списка имён у Point нет (#466).
     */
    override suspend fun handlersForPhone(phone: String): List<AppTarget> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val digits = phone.filter { it.isDigit() || it == '+' }
        listOf(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("tel:$digits")),
            Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$digits")),
        )
            .flatMap { pm.queryIntentActivities(it, 0) }
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map { AppTarget(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .distinctBy { it.packageName }
            .toList()
    }

    override suspend fun launchWithPhone(target: AppTarget, phone: String): Unit = withContext(Dispatchers.IO) {
        val digits = phone.filter { it.isDigit() || it == '+' }
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("tel:$digits")).apply {
            setClassName(target.packageName, target.activity)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override suspend fun handlersForMime(mime: String): List<AppTarget> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_VIEW).setType(mime)
        pm.queryIntentActivities(query, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map { AppTarget(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .distinctBy { it.packageName }
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

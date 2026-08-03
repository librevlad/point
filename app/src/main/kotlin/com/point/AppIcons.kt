package com.point

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Seam for the app-icon lookup — the VM depends on this, tests pass `{ null }`. */
fun interface AppIconResolver {
    fun iconFor(packageName: String): ImageBitmap?
}

/**
 * Resolves a real launcher icon for an app-capability bubble (#66 slice 4 polish).
 * PackageManager lookups are memoised — a handful of remembered apps, resolved once
 * per process; an uninstalled package quietly falls back to the stock glyph (null).
 */
@Singleton
class AppIcons @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppIconResolver {
    private val cache = mutableMapOf<String, ImageBitmap?>()

    override fun iconFor(packageName: String): ImageBitmap? = cache.getOrPut(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName)
                .toBitmap(ICON_PX, ICON_PX)
                .asImageBitmap()
        }.getOrNull()
    }

    private companion object {
        const val ICON_PX = 144
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppIconsModule {
    @Binds
    abstract fun appIconResolver(impl: AppIcons): AppIconResolver

    @Binds
    abstract fun selectionFrames(impl: AndroidSelectionFrames): SelectionFrames
}

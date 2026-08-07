package com.point

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.point.source.ObjectSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import javax.inject.Inject
import javax.inject.Singleton

fun interface AppIconResolver {
    fun iconFor(packageName: String): ImageBitmap?
}

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

    @Multibinds
    abstract fun objectSources(): Set<ObjectSource>

    @Binds
    @IntoSet
    abstract fun clipboardSource(impl: com.point.source.ClipboardSource): ObjectSource

    @Binds
    @IntoSet
    abstract fun cameraSource(impl: com.point.source.CameraSource): ObjectSource

    @Binds
    @IntoSet
    abstract fun voiceSource(impl: com.point.source.VoiceSource): ObjectSource

    @Binds
    @IntoSet
    abstract fun locationSource(impl: com.point.source.LocationSource): ObjectSource

    @Binds
    @IntoSet
    abstract fun receiveFileSource(impl: com.point.source.ReceiveFileSource): ObjectSource
}

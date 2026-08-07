package com.point

import android.content.Context
import com.point.core.flow.SharedTexts
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CacheSharedTexts @Inject constructor(
    @ApplicationContext private val context: Context,
) : SharedTexts {

    private fun dir(): File = File(context.cacheDir, DIR).apply { mkdirs() }

    override fun create(text: String): String =
        File.createTempFile("shared-", ".txt", dir()).apply { writeText(text) }.absolutePath

    override fun clear() {
        runCatching { dir().listFiles()?.forEach { it.delete() } }
    }

    private companion object {
        const val DIR = "shared-text"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SharedTextsModule {
    @Binds
    abstract fun sharedTexts(impl: CacheSharedTexts): SharedTexts
}

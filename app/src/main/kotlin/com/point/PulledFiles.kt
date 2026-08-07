package com.point

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File

fun interface PulledFileFactory {

    fun create(name: String): String
}

@Module
@InstallIn(SingletonComponent::class)
object PulledFilesModule {
    @Provides
    fun pulledFileFactory(@ApplicationContext context: Context): PulledFileFactory =
        PulledFileFactory { name ->
            File(File(context.cacheDir, "pulled").apply { mkdirs() }, name.replace('/', '_').replace('\\', '_')).absolutePath
        }
}

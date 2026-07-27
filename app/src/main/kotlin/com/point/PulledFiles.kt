package com.point

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File

/**
 * Where a pulled-from-PC entry lands before ingest (#161). A seam like [AppIconResolver]:
 * the VM stays JVM-testable (tests hand in a tmp-dir factory), the app provides cacheDir —
 * the OS may evict it, which is exactly right for a transit copy (ingest re-copies to scratch).
 */
fun interface PulledFileFactory {
    /** An absolute, writable path for a transit copy named [name]. */
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

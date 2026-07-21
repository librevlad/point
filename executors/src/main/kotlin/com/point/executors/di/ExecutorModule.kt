package com.point.executors.di

import com.point.core.flow.Executor
import com.point.core.flow.ExecutorRegistry
import com.point.executors.AiExecutor
import com.point.executors.DefaultExecutorRegistry
import com.point.executors.ImageExecutor
import com.point.executors.OpenUrlExecutor
import com.point.executors.PdfExecutor
import com.point.executors.SaveExecutor
import com.point.executors.ShareExecutor
import com.point.executors.TranslateExecutor
import com.point.executors.ZipExecutor
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds every Executor into the multibound `Set<Executor>` and exposes the
 * derived [ExecutorRegistry]. Adding an executor = adding one `@Binds @IntoSet`
 * line here; the Flow Graph then includes it with no other change.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExecutorModule {

    @Binds
    abstract fun registry(impl: DefaultExecutorRegistry): ExecutorRegistry

    @Binds @IntoSet abstract fun share(e: ShareExecutor): Executor
    @Binds @IntoSet abstract fun save(e: SaveExecutor): Executor
    @Binds @IntoSet abstract fun pdf(e: PdfExecutor): Executor
    @Binds @IntoSet abstract fun image(e: ImageExecutor): Executor
    @Binds @IntoSet abstract fun zip(e: ZipExecutor): Executor
    @Binds @IntoSet abstract fun translate(e: TranslateExecutor): Executor
    @Binds @IntoSet abstract fun ai(e: AiExecutor): Executor
    @Binds @IntoSet abstract fun openUrl(e: OpenUrlExecutor): Executor
}

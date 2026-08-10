package com.point.executors.di

import javax.inject.Qualifier

/**
 * Способности самого телефона — без тех, что объявил компьютер.
 *
 * Набор нужен, чтобы понять, объявил ли компьютер знакомое умение или новое: знакомое
 * сливается в одну способность, и компьютер становится её вторым исполнителем (#628).
 * Спросить об этом общий `Set<Capability>` нельзя — провайдер сам в него кладёт.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OwnCapabilities

package com.point.data.di

import javax.inject.Qualifier

/** The base directory for persistent history (filesDir/history). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HistoryDir

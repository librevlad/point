package com.point.data.di

import javax.inject.Qualifier

/** The local file the last-crash report is kept in (#11). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CrashLogFile

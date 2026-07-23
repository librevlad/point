package com.point.data.di

import javax.inject.Qualifier

/** The base directory for the private usage journal (filesDir/usage). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UsageDir

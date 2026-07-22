package com.point.data.di

import javax.inject.Qualifier

/** The base directory for persistent favorite chains (filesDir/favorites). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FavoritesDir

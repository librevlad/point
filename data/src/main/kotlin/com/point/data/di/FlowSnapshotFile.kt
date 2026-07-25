package com.point.data.di

import javax.inject.Qualifier

/** The single JSON file the flow journey snapshot lives in (#7) — outside scratch. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FlowSnapshotFile

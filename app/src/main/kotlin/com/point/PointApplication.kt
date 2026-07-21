package com.point

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point and Hilt DI root.
 *
 * App-scoped bindings — the ExecutorRegistry, the scratch ObjectStore and the
 * Gemini LlmClient — are provided through Hilt and injected. They are DI
 * instances, NOT global/static singletons or Kotlin `object`s (see the
 * "Никаких синглтонов" clarification in the spec and docs/DECISIONS.md).
 */
@HiltAndroidApp
class PointApplication : Application()

package com.point.executors

import com.point.core.flow.AiReadiness

internal val aiKeysReady = AiReadiness { true }

internal val aiKeysMissing = AiReadiness { false }

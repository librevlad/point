package com.point.core.flow

data class RealizerMeta(

    val priority: Int = 50,
    val kind: RealizerKind = RealizerKind.LOCAL,
)

enum class RealizerKind { LOCAL, CLOUD, REMOTE }

package com.point.core.model

data class Preview(
    val title: String,
    val lines: List<String> = emptyList(),
    val confirmLabel: String = "Подтвердить",
)

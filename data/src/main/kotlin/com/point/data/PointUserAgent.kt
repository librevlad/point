package com.point.data

const val POINT_USER_AGENT = "Point/0.2 (Android)"

internal fun pointHeaders(own: Map<String, String>, caller: Map<String, String>): Map<String, String> =
    mapOf("User-Agent" to POINT_USER_AGENT) + own + caller

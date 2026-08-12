package com.point.core.flow

const val POINT_USER_AGENT = "Point/0.2 (Android)"

fun pointHeaders(own: Map<String, String>, caller: Map<String, String>): Map<String, String> =
    mapOf("User-Agent" to POINT_USER_AGENT) + own + caller

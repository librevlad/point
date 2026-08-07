package com.point.core.flow

enum class CloudScope {

    MODELS,

    PUBLIC_LINK,
}

fun remembersConsent(scope: CloudScope): Boolean = scope == CloudScope.MODELS

interface PrivacyConsent {
    suspend fun allowed(scope: CloudScope): Boolean

    suspend fun allow(scope: CloudScope)

    suspend fun revoke(scope: CloudScope)
}

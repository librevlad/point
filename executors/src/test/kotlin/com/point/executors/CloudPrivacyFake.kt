package com.point.executors

import com.point.core.flow.CloudPrivacySettings
import com.point.core.flow.PrivacyLevel

fun privacyAt(level: PrivacyLevel = PrivacyLevel.DEFAULT) = object : CloudPrivacySettings {
    override fun level() = level
    override suspend fun setLevel(level: PrivacyLevel) = Unit
}

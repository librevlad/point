package com.point.executors

import com.point.core.flow.CloudPrivacySettings
import com.point.core.flow.PrivacyLevel

/**
 * Уровень «куда можно отправлять» в тестах: по умолчанию — тот же, что у человека, не открывавшего
 * настройки, то есть максимум бесплатного. Тест, который проверяет не приватность, не должен про
 * неё ничего знать.
 */
fun privacyAt(level: PrivacyLevel = PrivacyLevel.DEFAULT) = object : CloudPrivacySettings {
    override fun level() = level
    override suspend fun setLevel(level: PrivacyLevel) = Unit
}

package com.point.core.flow

/**
 * Спросить у телефона, есть ли рабочая сеть, — перед тем как Point выйдет наружу
 * (#690, #691). Отрицательный ответ обязан быть фактом системы (Android
 * `NetworkCapabilities.NET_CAPABILITY_VALIDATED`), а не догадкой по одной неудачной
 * попытке: слабая, но живая связь — это работа, а не отказ. Реализация — за DI в
 * `:data` (см. `AndroidNetworkAvailability`), этот модуль об Android не знает.
 */
fun interface NetworkAvailability {
    fun isAvailable(): Boolean
}

/** Слова человеку, когда сети действительно нет, — телефон подтвердил это сам. */
const val NO_NETWORK_TEXT = "На телефоне нет интернета. Подключитесь и попробуйте ещё раз."

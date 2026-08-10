package com.point.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.point.core.flow.NetworkAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Факт системы, а не догадка по одной неудачной попытке (#690, #691): Android уже
 * знает, прошла ли проверка сети, — `NET_CAPABILITY_VALIDATED` значит «есть путь в
 * интернет и он отвечает», а не просто «радио включено». Слабая, но живая связь —
 * это `true` здесь: дальше со связью работает сама попытка, а не эта проверка.
 *
 * Если спросить систему не вышло (`SecurityException` и подобное на редком
 * устройстве) — считаем, что сеть есть: эта проверка только ускоряет честный отказ,
 * а не подменяет его собой.
 */
@Singleton
class AndroidNetworkAvailability @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkAvailability {

    private val manager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    override fun isAvailable(): Boolean = runCatching {
        val cm = manager ?: return@runCatching true
        val active = cm.activeNetwork ?: return@runCatching false
        val capabilities = cm.getNetworkCapabilities(active) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)
}

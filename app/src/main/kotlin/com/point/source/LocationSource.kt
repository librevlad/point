package com.point.source

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.LocationManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.Locale
import javax.inject.Inject

class LocationSource @Inject constructor() : ObjectSource {

    override val id = "location"
    override val label = "Место"

    override val what = "превратить в объект то, где вы сейчас"
    override val icon = "map"

    override val permissions = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    override fun isAvailable(context: Context): Boolean =
        ContextCompat.getSystemService(context, LocationManager::class.java) != null

    override suspend fun request(context: Context): Intent? = null

    override suspend fun read(context: Context, data: Intent?): Produced? {
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java)
        // С одним лишь «примерным» местом точные провайдеры бросаются — это не повод
        // терять сетевые: каждый провайдер читается сам за себя.
        val location = manager?.let { m ->
            runCatching { m.getProviders(true) }.getOrDefault(emptyList())
                .mapNotNull { provider -> runCatching { m.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
        }

        if (location == null) {

            Toast.makeText(context, "Место пока не определилось", Toast.LENGTH_SHORT).show()
            return null
        }

        val address = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        }.getOrNull()

        val body = placeText(location.latitude, location.longitude, address)
        val file = java.io.File.createTempFile("place-", ".txt", context.cacheDir)
        file.writeText(body)

        return Produced(
            android.net.Uri.fromFile(file).toString(),
            "text/plain",
            com.point.core.flow.textObjectName(body),
        )
    }
}

fun placeText(lat: Double, lon: Double, address: String?): String {
    val coords = String.format(Locale.US, "%.6f, %.6f", lat, lon)
    return if (address.isNullOrBlank()) coords else "$address\n$coords"
}

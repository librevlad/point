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

/**
 * Текущее место как объект (#246).
 *
 * Единственный источник, которому нужно настоящее разрешение, — поэтому он и делался последним.
 * Объектом становится текст: адрес, если система смогла его назвать, и координаты, которые
 * измерены. Дальше человек сам решает, что с этим делать: у Point уже есть «Показать на карте» и
 * «Построить маршрут».
 */
class LocationSource @Inject constructor() : ObjectSource {

    override val id = "location"
    override val label = "Место"
    override val icon = "map"

    override val permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun isAvailable(context: Context): Boolean =
        ContextCompat.getSystemService(context, LocationManager::class.java) != null

    override suspend fun request(context: Context): Intent? = null

    override suspend fun read(context: Context, data: Intent?): Produced? {
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java)
        val location = runCatching {
            manager?.getProviders(true).orEmpty()
                .mapNotNull { provider -> manager?.getLastKnownLocation(provider) }
                .maxByOrNull { it.time }
        }.getOrNull()

        if (location == null) {
            // «Место пока не определилось» — это не поломка: приёмник мог ещё не поймать сигнал,
            // и молчание здесь было бы враньём про причину.
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
        // Имя месту даёт оно само — адрес или координаты (#533). `place-1754325912345.txt` в
        // «Недавнем» не отличалось от соседнего такого же ничем, кроме времени под строкой.
        return Produced(
            android.net.Uri.fromFile(file).toString(),
            "text/plain",
            com.point.core.flow.textObjectName(body),
        )
    }
}

/**
 * Каким текстом место становится объектом: сначала адрес (если система его назвала), затем
 * координаты. Координаты — измеренное, адрес — истолкование, и порядок здесь тот же, что во всём
 * Point: измеренное не прячется за истолкованием.
 */
fun placeText(lat: Double, lon: Double, address: String?): String {
    val coords = String.format(Locale.US, "%.6f, %.6f", lat, lon)
    return if (address.isNullOrBlank()) coords else "$address\n$coords"
}

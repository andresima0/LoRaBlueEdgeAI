package com.android.lorablueedge.weather

import android.content.Context
import androidx.core.content.edit

/**
 * Where the totem is and how much roof drains into the cistern.
 *
 * @property roofAreaM2 Optional. Without it the rain forecast is shown but
 *                      no rain is credited to the water autonomy.
 */
data class WeatherConfig(
    val apiKey: String,
    val latitude: Double?,
    val longitude: Double?,
    val roofAreaM2: Double?
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && latitude != null && longitude != null
}

/** SharedPreferences persistence for [WeatherConfig]. */
class WeatherConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("weather_config", Context.MODE_PRIVATE)

    fun load() = WeatherConfig(
        apiKey = prefs.getString("api_key", "") ?: "",
        latitude = prefs.getString("latitude", null)?.toDoubleOrNull(),
        longitude = prefs.getString("longitude", null)?.toDoubleOrNull(),
        roofAreaM2 = prefs.getString("roof_area_m2", null)?.toDoubleOrNull()
    )

    fun save(config: WeatherConfig) {
        // Stored as text so the coordinates keep their full precision.
        prefs.edit {
            putString("api_key", config.apiKey)
            putString("latitude", config.latitude?.toString())
            putString("longitude", config.longitude?.toString())
            putString("roof_area_m2", config.roofAreaM2?.toString())
        }
    }
}
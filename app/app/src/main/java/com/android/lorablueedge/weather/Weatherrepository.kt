package com.android.lorablueedge.weather

import android.content.Context
import androidx.core.content.edit
import com.android.lorablueedge.analytics.ForecastSlot
import com.android.lorablueedge.analytics.RainForecast
import com.android.lorablueedge.analytics.RainForecastCalculator
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException

/**
 * Fetches the rain forecast (RF05) and keeps the last good one on disk, so
 * the app starts with a recent value even before the first request
 * succeeds. The internet is optional: leak detection (RNF01) never depends
 * on this class, and without a forecast the autonomy simply gets no rain
 * credit.
 */
class WeatherRepository(context: Context) {

    private val prefs = context.getSharedPreferences("weather_cache", Context.MODE_PRIVATE)

    private val api: OpenWeatherApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.openweathermap.org/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenWeatherApi::class.java)
    }

    /**
     * Blocking network call: run it on a background thread.
     * @throws IOException on any failure; the cached forecast is kept.
     */
    fun refresh(config: WeatherConfig, nowMs: Long = System.currentTimeMillis()): RainForecast {
        val lat = config.latitude ?: throw IOException("latitude not set")
        val lon = config.longitude ?: throw IOException("longitude not set")

        val response = api.forecast(lat, lon, config.apiKey, "metric").execute()
        if (!response.isSuccessful) throw IOException(describeHttpError(response.code()))

        val entries = response.body()?.list.orEmpty()
        // An empty list is a broken response, not "no rain": do not store a 0 mm.
        if (entries.isEmpty()) throw IOException("empty forecast")

        val slots = entries.map { ForecastSlot(it.dt, it.rain?.threeHours, it.pop) }
        val forecast = RainForecast(RainForecastCalculator.expectedRainMm(slots, nowMs), nowMs)
        save(forecast)
        return forecast
    }

    /** The last forecast fetched successfully, or null if there is none yet. */
    fun cached(): RainForecast? {
        if (!prefs.contains("fetched_at_ms")) return null
        return RainForecast(
            expectedMm24h = prefs.getFloat("expected_mm_24h", 0f).toDouble(),
            fetchedAtMs = prefs.getLong("fetched_at_ms", 0L)
        )
    }

    private fun save(forecast: RainForecast) {
        prefs.edit {
            putFloat("expected_mm_24h", forecast.expectedMm24h.toFloat())
            putLong("fetched_at_ms", forecast.fetchedAtMs)
        }
    }

    private fun describeHttpError(code: Int) = when (code) {
        401 -> "invalid API key (a new key can take a while to activate)"
        429 -> "request limit reached"
        else -> "HTTP $code"
    }
}
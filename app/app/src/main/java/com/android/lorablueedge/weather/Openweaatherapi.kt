package com.android.lorablueedge.weather

import com.google.gson.annotations.SerializedName
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * OpenWeather "5 day / 3 hour forecast" (free plan). Only the fields the
 * app needs are mapped: "rain" is absent from a step when no rain is
 * expected, hence every rain field is nullable.
 */
interface OpenWeatherApi {

    @GET("data/2.5/forecast")
    fun forecast(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
        @Query("appid") apiKey: String,
        @Query("units") units: String
    ): Call<ForecastResponse>
}

data class ForecastResponse(
    val list: List<ForecastEntry>?
)

data class ForecastEntry(
    /** End of the 3-hour step, unix seconds UTC. */
    val dt: Long,
    /** Probability of precipitation, 0..1. */
    val pop: Double?,
    val rain: RainVolume?
)

data class RainVolume(
    /** Rain volume of the 3 hours before [ForecastEntry.dt], in mm. */
    @SerializedName("3h") val threeHours: Double?
)
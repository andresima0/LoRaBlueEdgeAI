package com.android.lorablueedge.chart

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists telemetry history using plain SharedPreferences + JSON instead
 * of Room. This exists purely to avoid a KSP/annotation-processor
 * dependency that conflicted with this project's AGP version (AGP 9.2.1
 * embeds its own Kotlin plugin on the classpath without a version,
 * which collides with explicitly declaring org.jetbrains.kotlin.android
 * — see project history). SharedPreferences has no such requirement: it's
 * part of the Android SDK with zero extra Gradle setup.
 *
 * Trade-offs versus Room, accepted deliberately for this use case:
 * - No real querying — every read parses the ENTIRE stored JSON array and
 *   filters in Kotlin. Fine here because the retention window is short
 *   (1 hour safety margin, 10-minute UI window) so the array never grows
 *   large for a single-installation hobby/IoT-class app.
 * - No automatic reactive updates from a database trigger. Reactivity is
 *   instead provided by a process-wide singleton ([Cache]) that this class
 *   updates directly on every write, then mirrors to disk so the history
 *   survives app restarts.
 *
 * The shared mutable state is isolated inside the [Cache] object so it is
 * explicit, named, and easy to replace or mock in tests — previously it
 * lived as anonymous @Volatile fields in a companion object, which made the
 * implicit singleton behaviour invisible.
 */
class TelemetryStore(context: Context) {

    // Use applicationContext to avoid leaking an Activity/Service Context
    // across the process lifetime of the Cache singleton.
    private val appContext: Context = context.applicationContext

    private val prefs = appContext.getSharedPreferences(
        "lorablue_telemetry", Context.MODE_PRIVATE
    )

    private val retentionMillis = 60L * 60_000 // keep 1h of headroom beyond the 10-min UI window

    // ------------------------------------------------------------------
    // Process-wide singleton — explicit object instead of hidden statics
    // inside a companion object, so the singleton nature is obvious and
    // the object can be referenced directly in tests if needed.
    // ------------------------------------------------------------------
    private object Cache {
        @Volatile
        var samples: MutableList<TelemetrySample>? = null

        val liveData = MutableLiveData<List<TelemetrySample>>(emptyList())

        // Singleton lock — all TelemetryStore instances share this lock so
        // concurrent record() calls from BleViewModel's BLE thread and
        // ChartViewModel's observer thread are safely serialised.
        val lock = Any()
    }

    val samplesLiveData: LiveData<List<TelemetrySample>> = Cache.liveData

    private fun ensureLoaded() {
        if (Cache.samples != null) return
        synchronized(Cache.lock) {
            if (Cache.samples == null) {
                Cache.samples = loadFromDisk().toMutableList()
                Cache.liveData.postValue(Cache.samples!!.toList())
            }
        }
    }

    fun record(sample: TelemetrySample) {
        ensureLoaded()
        synchronized(Cache.lock) {
            val list = Cache.samples!!
            list.add(sample)

            val cutoff = System.currentTimeMillis() - retentionMillis
            list.removeAll { it.timestamp < cutoff }

            Cache.liveData.postValue(list.toList())
            saveToDisk(list)
        }
    }

    /**
     * Returns samples for [deviceId] within the last [windowMillis],
     * oldest first. Unlike Room's observeRange, this isn't a separate
     * reactive query — ChartViewModel maps the shared samplesLiveData
     * through this filter on every emission (see ChartViewModel).
     */
    fun filterRange(
        all: List<TelemetrySample>,
        deviceId: Int,
        windowMillis: Long
    ): List<TelemetrySample> {
        val since = System.currentTimeMillis() - windowMillis
        return all
            .filter { it.deviceId == deviceId && it.timestamp >= since }
            .sortedBy { it.timestamp }
    }

    private fun loadFromDisk(): List<TelemetrySample> {
        val raw = prefs.getString(KEY_SAMPLES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                TelemetrySample(
                    deviceId = obj.getInt("deviceId"),
                    timestamp = obj.getLong("timestamp"),
                    waterLevel = obj.getDouble("waterLevel"),
                    batteryPercent = obj.getDouble("batteryPercent"),
                    rssiDbm = obj.getDouble("rssiDbm"),
                    pumpOn = if (obj.has("pumpOn") && !obj.isNull("pumpOn")) obj.getBoolean("pumpOn") else null,
               )
            }
        } catch (e: Exception) {
            // Corrupted/old-format JSON — start fresh rather than crash.
            emptyList()
        }
    }

    private fun saveToDisk(samples: List<TelemetrySample>) {
        val array = JSONArray()
        samples.forEach { sample ->
            val obj = JSONObject().apply {
                put("deviceId", sample.deviceId)
                put("timestamp", sample.timestamp)
                put("waterLevel", sample.waterLevel)
                put("batteryPercent", sample.batteryPercent)
                put("rssiDbm", sample.rssiDbm)
                put("pumpOn", sample.pumpOn ?: JSONObject.NULL)
            }
            array.put(obj)
        }
        prefs.edit { putString(KEY_SAMPLES, array.toString()) }
    }

    companion object {
        private const val KEY_SAMPLES = "samples_json"
    }
}
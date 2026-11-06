package com.android.lorablueedge.service

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.android.lorablueedge.analytics.AutonomyEstimate
import com.android.lorablueedge.analytics.ConsumptionSummary
import com.android.lorablueedge.analytics.LeakAssessment
import com.android.lorablueedge.analytics.RainForecast
import com.android.lorablueedge.ble.BleConnectionState
import com.android.lorablueedge.data.TelemetryReading
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide state published by [LoRaBlueService] and observed by the UI.
 *
 * The service outlives any Activity/ViewModel, so the state it produces
 * cannot live inside a ViewModel anymore. The ViewModel just exposes these
 * LiveData objects; the Activity code observing them did not need to change.
 *
 * Connection state and the last reading per device are "sticky" on purpose:
 * reopening the app immediately shows the latest known values.
 * [debugLog] and [mqttStatus] are one-shot events (they end up as Toasts),
 * so they use [SingleLiveEvent] to avoid replaying an old message every
 * time the Activity comes back to the foreground.
 */
object GatewayHub {
    val connectionState = MutableLiveData<BleConnectionState>(BleConnectionState.Disconnected)
    val cisternData = MutableLiveData<TelemetryReading.Cistern>()
    val tankData = MutableLiveData<TelemetryReading.Tank>()
    val cisternConsumption = MutableLiveData<ConsumptionSummary>()
    val tankConsumption = MutableLiveData<ConsumptionSummary>()
    val cisternLeak = MutableLiveData<LeakAssessment>()
    val tankLeak = MutableLiveData<LeakAssessment>()
    val autonomy = MutableLiveData<AutonomyEstimate>()
    val rain = MutableLiveData<RainForecast>()
    val weatherStatus = SingleLiveEvent<String>()
    val debugLog = SingleLiveEvent<String>()
    val mqttStatus = SingleLiveEvent<String>()
}

/**
 * LiveData that delivers each value to an observer at most once.
 * Intended for a single observer (the main Activity).
 */
class SingleLiveEvent<T> : MutableLiveData<T>() {

    private val pending = AtomicBoolean(false)

    override fun observe(owner: LifecycleOwner, observer: Observer<in T>) {
        super.observe(owner, Observer { value ->
            if (pending.compareAndSet(true, false)) observer.onChanged(value)
        })
    }

    override fun setValue(value: T) {
        pending.set(true)
        super.setValue(value)
    }
}
package com.android.lorablueedge.mqtt

import android.content.Context
import androidx.core.content.edit

// ─────────────────────────────────────────────────────────────────────────────
// Platform selector
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Supported IoT platforms. Each has its own authentication model:
 *
 * [THINGSBOARD]
 *   - Fixed topic: "v1/devices/me/telemetry"
 *   - Auth: per-device access token sent as MQTT Username (no password).
 *   - Two separate connections are opened per telemetry cycle so each device
 *     is identified by its own token.
 *
 * [KONKER]
 *   - Configurable topic per device (Cistern / Tank).
 *   - Auth: shared Username + Password for the Konker application.
 *   - One connection is opened per publish; topic distinguishes the device.
 *
 * Both platforms can be enabled at the same time (see [MqttConfig.enabled]) —
 * this enum only distinguishes *which kind* of config a given instance is,
 * it does not imply any single one is "the active" platform anymore.
 */
enum class IotPlatform(val displayName: String) {
    THINGSBOARD("ThingsBoard"),
    KONKER("Konker")
}

// ─────────────────────────────────────────────────────────────────────────────
// Config model — sealed to make platform-specific fields explicit and safe
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Sealed class: each subclass carries exactly the fields that make sense for
 * its platform — no nullable placeholders or "unused" fields.
 *
 * Both share [server], [port], [enabled], and [brokerUrl] via the base class.
 *
 * [enabled] lets each platform be turned on/off independently. Both
 * ThingsBoard and Konker can be enabled simultaneously — BleViewModel then
 * publishes every incoming reading to each enabled config in turn (see
 * BleViewModel.mqttConfigs). A disabled config is still persisted (so its
 * fields aren't lost) but is simply skipped when building the list of
 * configs to publish to.
 */
sealed class MqttConfig {

    abstract val server: String
    abstract val port: String
    abstract val enabled: Boolean

    val brokerUrl: String
        get() = "tcp://$server:${port.ifBlank { "1883" }}"

    val platform: IotPlatform
        get() = when (this) {
            is ThingsBoard -> IotPlatform.THINGSBOARD
            is Konker      -> IotPlatform.KONKER
        }

    // ── ThingsBoard ──────────────────────────────────────────────────────────

    /**
     * @param cisternToken Access token for the Cistern device (MQTT Username).
     * @param tankToken    Access token for the Tank device (MQTT Username).
     * @param enabled      Whether telemetry should currently be published to ThingsBoard.
     */
    data class ThingsBoard(
        override val server: String,
        override val port: String,
        val cisternToken: String,
        val tankToken: String,
        override val enabled: Boolean = true
    ) : MqttConfig() {

        /** Fixed telemetry topic — the same for every ThingsBoard device. */
        val telemetryTopic: String get() = "v1/devices/me/telemetry"

        val isCisternConfigured: Boolean
            get() = server.isNotBlank() && cisternToken.isNotBlank()

        val isTankConfigured: Boolean
            get() = server.isNotBlank() && tankToken.isNotBlank()
    }

    // ── Konker ───────────────────────────────────────────────────────────────

    /**
     * @param user         Konker application username (shared by both devices).
     * @param pass         Konker application password.
     * @param cisternTopic MQTT topic for the Cistern device on Konker.
     * @param tankTopic    MQTT topic for the Tank device on Konker.
     * @param enabled      Whether telemetry should currently be published to Konker.
     */
    data class Konker(
        override val server: String,
        override val port: String,
        val user: String,
        val pass: String,
        val cisternTopic: String,
        val tankTopic: String,
        override val enabled: Boolean = true
    ) : MqttConfig() {

        val isCisternConfigured: Boolean
            get() = server.isNotBlank() && user.isNotBlank() && cisternTopic.isNotBlank()

        val isTankConfigured: Boolean
            get() = server.isNotBlank() && user.isNotBlank() && tankTopic.isNotBlank()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Persistence — SharedPreferences, one flat namespace for both platforms
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Persists the config for BOTH platforms independently, each with its own
 * "enabled" flag, so any combination (neither / ThingsBoard only / Konker
 * only / both) can be active at once.
 *
 * Legacy note: this store used to persist a single "platform" key meaning
 * "the one active platform". That key is still written (it now just means
 * "the platform last edited in the dialog", used only to pick which section
 * the settings dialog opens on) and is also used as a one-time migration
 * hint: if a device upgrades from the old single-platform behavior and
 * neither "*_enabled" key has ever been written yet, the platform that used
 * to be "the active one" is treated as enabled and the other as disabled,
 * preserving old behavior until the user explicitly changes it.
 */
class MqttConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("iot_platform_config", Context.MODE_PRIVATE)

    /**
     * Loads the config for a **specific** platform regardless of whether it
     * is enabled. Used by [MqttConfigDialog] to pre-fill both form sections
     * so previously entered credentials are not lost when the user switches
     * between platforms in the spinner, and by [loadAllEnabled] to build the
     * publish list.
     */
    fun loadPlatform(platform: IotPlatform): MqttConfig {
        // Legacy single-platform key — used only as a migration fallback
        // for the enabled flag below, when no "*_enabled" key exists yet.
        val legacyActivePlatform = prefs.getString("platform", IotPlatform.THINGSBOARD.name)

        return when (platform) {
            IotPlatform.THINGSBOARD -> MqttConfig.ThingsBoard(
                server       = prefs.getString("tb_server", "") ?: "",
                port         = prefs.getString("tb_port", "1883") ?: "1883",
                cisternToken = prefs.getString("tb_cistern_token", "") ?: "",
                tankToken    = prefs.getString("tb_tank_token",    "") ?: "",
                enabled      = prefs.getBoolean(
                    "tb_enabled",
                    legacyActivePlatform == IotPlatform.THINGSBOARD.name
                )
            )
            IotPlatform.KONKER -> MqttConfig.Konker(
                server       = prefs.getString("konker_server", "") ?: "",
                port         = prefs.getString("konker_port", "1883") ?: "1883",
                user         = prefs.getString("konker_user",          "") ?: "",
                pass         = prefs.getString("konker_pass",          "") ?: "",
                cisternTopic = prefs.getString("konker_cistern_topic", "") ?: "",
                tankTopic    = prefs.getString("konker_tank_topic",    "") ?: "",
                enabled      = prefs.getBoolean(
                    "konker_enabled",
                    legacyActivePlatform == IotPlatform.KONKER.name
                )
            )
        }
    }

    /**
     * Returns every platform config that is BOTH enabled AND has at least
     * one device (Cistern or Tank) fully configured. This is exactly the
     * list [BleViewModel] should publish every incoming reading to — an
     * enabled-but-empty config would otherwise be a silent no-op inside
     * MqttPublisher, so filtering it out here keeps that list meaningful.
     */
    fun loadAllEnabled(): List<MqttConfig> =
        IotPlatform.values()
            .map { loadPlatform(it) }
            .filter { it.enabled && hasAnyDeviceConfigured(it) }

    private fun hasAnyDeviceConfigured(config: MqttConfig): Boolean = when (config) {
        is MqttConfig.ThingsBoard -> config.isCisternConfigured || config.isTankConfigured
        is MqttConfig.Konker      -> config.isCisternConfigured || config.isTankConfigured
    }

    /**
     * Loads a single config to pre-select which section [MqttConfigDialog]
     * opens on. Kept for backward compatibility with call sites that just
     * want "some" current config — prefer [loadPlatform] or [loadAllEnabled]
     * for anything that cares about a specific platform or the publish set.
     */
    fun load(): MqttConfig {
        val platformName = prefs.getString("platform", IotPlatform.THINGSBOARD.name)
            ?: IotPlatform.THINGSBOARD.name

        return when (platformName) {
            IotPlatform.KONKER.name -> loadPlatform(IotPlatform.KONKER)
            else -> loadPlatform(IotPlatform.THINGSBOARD)
        }
    }

    fun save(config: MqttConfig) {
        prefs.edit {
            // Remembers only which section the dialog should default to
            // next time it opens — no longer means "the one active platform".
            putString("platform", config.platform.name)

            when (config) {
                is MqttConfig.ThingsBoard -> {
                    putString("tb_server",        config.server)
                    putString("tb_port",          config.port)
                    putString("tb_cistern_token", config.cisternToken)
                    putString("tb_tank_token",    config.tankToken)
                    putBoolean("tb_enabled",      config.enabled)
                }
                is MqttConfig.Konker -> {
                    putString("konker_server",        config.server)
                    putString("konker_port",          config.port)
                    putString("konker_user",          config.user)
                    putString("konker_pass",          config.pass)
                    putString("konker_cistern_topic", config.cisternTopic)
                    putString("konker_tank_topic",    config.tankTopic)
                    putBoolean("konker_enabled",       config.enabled)
                }
            }
        }
    }
}
#include "radio.h"

unsigned long last_tx_time = 0;
const unsigned long tx_interval = 1e4;

void set_lora_tx_pins(void) {
    radio.setRfSwitchPins(LORA_RF_SW, LORA_RF_SW);
}

void log_telemetry(const TelemetryData &d) {
    // An invalid reading is logged as null instead of a misleading number.
    char level[12];
    if (d.water_valid) {
        snprintf(level, sizeof(level), "%.2f", d.water_lvl);
    } else {
        snprintf(level, sizeof(level), "null");
    }

    if (d.node_type == DEVICE_TYPE_CISTERN) {
        Serial.printf(
            "[TX] {\"node_id\":%d,\"water_lvl\":%s,\"water_pump\":%s,\"batt_lvl\":%.1f}\n",
            d.node_id, level, d.pump_status ? "true" : "false", d.bat_percent);
    } else if (d.node_type == DEVICE_TYPE_TANK) {
        Serial.printf(
            "[TX] {\"node_id\":%d,\"water_lvl\":%s,\"batt_lvl\":%.1f}\n",
            d.node_id, level, d.bat_percent);
    } else {
        Serial.print(F("[TX] WARNING: unknown node type="));
        Serial.println(d.node_type);
    }
}

bool send_data(TelemetryData d) {
    Serial.println(F("\n-- Transmitindo via LoRa --"));
    log_telemetry(d);

    digitalWrite(LED_GREEN, LOW);
    int transmitState = radio.transmit((uint8_t *)&d, sizeof(TelemetryData));
    digitalWrite(LED_GREEN, HIGH);

    if (transmitState == RADIOLIB_ERR_NONE) {
        Serial.println(F("Success!"));
        return true;
    } else {
        Serial.print(F("Failed! Error code: "));
        Serial.println(transmitState);
        return false;
    }
}
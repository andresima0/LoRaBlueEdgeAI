#include "bt.h"
#include "radio.h"
#include <Arduino.h>

void setup() {
    Serial.begin(115200);

    // Security delay for USB native da XIAO nRF52840
    while (!Serial && millis() < 5000);

    while (true) {
        bool status = enable_radio();

        if (!status) {
            Serial.println(F("Failed to initialize SX1262. Trying again..."));
            delay(1000);
            continue;
        }

        set_lora_rx_pins();
        break;
    }

    Serial.println(F("\n[SX1262] Initializing RX Node (LoRaBlue IoT Gateway)..."));
    setupBLE();
}

// ----------------------------------------------------------------------
// Prints the TelemetryData just received over LoRa as a JSON line,
// mirroring the [TX] {json} log on the transmitter side.
// ----------------------------------------------------------------------
void log_received(const TelemetryData &d, float rssi) {
    if (d.node_type == DEVICE_TYPE_CISTERN) {
        Serial.printf(
            "[RX] {\"node_id\":%d,\"water_lvl\":%.2f,\"water_ok\":%s,\"water_pump\":%s,"
            "\"batt_lvl\":%.1f,\"rssi_lvl\":%.1f}\n",
            d.node_id, d.water_lvl, d.water_valid ? "true" : "false",
            d.pump_status ? "true" : "false", d.bat_percent, rssi);
    } else if (d.node_type == DEVICE_TYPE_TANK) {
        Serial.printf(
            "[RX] {\"node_id\":%d,\"water_lvl\":%.2f,\"water_ok\":%s,"
            "\"batt_lvl\":%.1f,\"rssi_lvl\":%.1f}\n",
            d.node_id, d.water_lvl, d.water_valid ? "true" : "false",
            d.bat_percent, rssi);
    } else {
        Serial.print(F("[RX] WARNING: unknown node type="));
        Serial.println(d.node_type);
    }
}

// ----------------------------------------------------------------------
// BLE JSON for a Cistern reading. Fields: node_id, node_type, water_lvl,
// water_ok, water_pump, batt_lvl, rssi_lvl. When water_ok is false the
// app must ignore water_lvl.
// ----------------------------------------------------------------------
void dispatch_cistern(const TelemetryData &d, float rssi) {
    char jsonPayload[192];
    snprintf(jsonPayload, sizeof(jsonPayload),
             "{\"node_id\":%d,\"node_type\":%d,\"water_lvl\":%.2f,\"water_ok\":%s,"
             "\"water_pump\":%s,\"batt_lvl\":%.1f,\"rssi_lvl\":%.1f}\n",
             d.node_id, d.node_type, d.water_lvl, d.water_valid ? "true" : "false",
             d.pump_status ? "true" : "false", d.bat_percent, rssi);

    bleSend(jsonPayload);
    Serial.print(F("[BLE] Cistern JSON dispatched: "));
    Serial.print(jsonPayload);
}

// ----------------------------------------------------------------------
// BLE JSON for a Tank reading. Fields: node_id, node_type, water_lvl,
// water_ok, batt_lvl, rssi_lvl.
// ----------------------------------------------------------------------
void dispatch_tank(const TelemetryData &d, float rssi) {
    char jsonPayload[192];
    snprintf(jsonPayload, sizeof(jsonPayload),
             "{\"node_id\":%d,\"node_type\":%d,\"water_lvl\":%.2f,\"water_ok\":%s,"
             "\"batt_lvl\":%.1f,\"rssi_lvl\":%.1f}\n",
             d.node_id, d.node_type, d.water_lvl, d.water_valid ? "true" : "false",
             d.bat_percent, rssi);

    bleSend(jsonPayload);
    Serial.print(F("[BLE] Tank JSON dispatched: "));
    Serial.print(jsonPayload);
}

void loop() {

    if (hasNewBleCommand) {
        hasNewBleCommand = false;
        String command = bleCommandBuffer;

        if (command.equalsIgnoreCase("PING")) {
            digitalWrite(LED_RED, LOW);
            bleSend("{\"debug\":\"PONG\"}\n");
        } else if (command.equalsIgnoreCase("CLEAR")) {
            digitalWrite(LED_RED, HIGH);
            bleSend("{\"debug\":\"CLRD\"}\n");
        }
    }

    //Gateway Heartbeat
    if (millis() - lastHeartbeat > 5000) {
        Serial.println(F("[System] Gateway is listening..."));
        lastHeartbeat = millis();
    }

    if (radio_rx_status == true) {
        radio_rx_status = false;

        float rssi = radio.getRSSI();

        if (receive_data(&data)) {
            log_received(data, rssi);

            if (!Bluefruit.connected()) {
                Serial.println(F("[BLE] No device connected."));
            } else if (data.node_type == DEVICE_TYPE_CISTERN) {
                dispatch_cistern(data, rssi);
            } else if (data.node_type == DEVICE_TYPE_TANK) {
                dispatch_tank(data, rssi);
            } else {
                Serial.print(F("[receiver] Unknown node type, packet dropped: "));
                Serial.println(data.node_type);
            }

        } else {
            Serial.println(F("Failed to receive data (CRC error)."));
        }
    }

    delay(10);
}
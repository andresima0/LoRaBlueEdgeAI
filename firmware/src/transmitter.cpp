#include "radio.h"
#include "sensors.h"
#include <Arduino.h>

#ifndef NODE_ID
#error "NODE_ID not defined — set build_flags = -DNODE_ID=<1-254> in platformio.ini (unique per physical node)"
#endif

#ifndef NODE_TYPE
#error "NODE_TYPE not defined — set build_flags = -DNODE_TYPE=DEVICE_TYPE_CISTERN (or DEVICE_TYPE_TANK) in platformio.ini"
#endif

void setup() {
    Serial.begin(115200);

    // Initialize LoRa Radio
    bool radio_status = enable_radio();

    if (radio_status == false) {
        delay(3e3);
        Serial.println("Radio functionality must be on!");
        delay(3e3);

        while (1) {
            Serial.println("Check LoRa connections!");
            delay(100);
        }
    }

    pinMode(LED_GREEN, OUTPUT);
    digitalWrite(LED_GREEN, HIGH);

    // Hardware setup
    set_lora_tx_pins();
    // Initialize I2C and VL53L1X
    if (!setup_sensors()) {
        Serial.println(F("[TX] WARNING: distance sensor init failed"));
    }

    data.node_id = NODE_ID;
    data.node_type = NODE_TYPE;

    Serial.print(F("[TX] Node ID: "));
    Serial.println(NODE_ID);
    Serial.print(F("[TX] Node type: "));
    Serial.println(NODE_TYPE == DEVICE_TYPE_CISTERN ? "Cistern" : "Tank");
}

void loop() {

    if (millis() - last_tx_time >= tx_interval || last_tx_time == 0) {
        last_tx_time = millis();

        float level_m = 0.0f;
        data.water_valid = get_water_lvl(&level_m);
        data.water_lvl = data.water_valid ? level_m : 0.0f;
        data.bat_percent = get_battery_percent();

     #if NODE_TYPE == DEVICE_TYPE_CISTERN
            data.pump_status = get_pump_status();
        #else
            data.pump_status = false;
        #endif

        send_data(data);
    }
}
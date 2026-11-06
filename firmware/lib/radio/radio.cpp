#include "radio.h"

SX1262 radio = new Module(LORA_NSS, LORA_DIO1, LORA_NRST, LORA_BUSY);
TelemetryData data = {0};

bool enable_radio(void) {
    // Carrier Frequency: 915MHz
    // Bandwidth: 125.0kHz
    // Spreading Factor: 7
    // Coding Rate: 5
    // 1-byte LoRa Sync Word: 0x12
    // Output Power: 22dBm
    // LoRa Preamble Length in Symbols: 8
    int state = radio.begin(915.0, 125.0, 7, 5, 0x12, 22, 8);

    if (state == RADIOLIB_ERR_NONE) {
        Serial.println(F("[SX1262] Initialization successful!"));
        return true;

    } else {
        Serial.print(F("[SX1262] Initialization failed, code: "));
        Serial.println(state);
        return false;
    }
}
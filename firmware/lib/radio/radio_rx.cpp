#include "radio.h"

volatile bool radio_rx_status = false;

void lora_rx_callback(void) { 
    radio_rx_status = true; 
}

void set_lora_rx_pins(void) {
    radio.setRfSwitchPins(LORA_RF_SW, RADIOLIB_NC);
    radio.setPacketReceivedAction(lora_rx_callback);

    Serial.println(F("[SX1262] Starting continuous receive mode..."));
    radio.startReceive();
}

bool receive_data(TelemetryData *d) {
    int status = radio.readData((uint8_t *)d, sizeof(TelemetryData));
    return (status == RADIOLIB_ERR_NONE);
}
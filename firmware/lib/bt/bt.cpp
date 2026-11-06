#include "bt.h"

BLEUart bleuart;

volatile bool loraPacketReady = false;
String bleCommandBuffer = "";
volatile bool hasNewBleCommand = false;
unsigned long lastHeartbeat = 0;

void setupBLE(void) {
    Serial.println(F("[BLE] Initializing Bluetooth Low Energy..."));

    Bluefruit.configPrphConn(247, 247, 2, 2);
    Bluefruit.configPrphBandwidth(BANDWIDTH_MAX);
    Bluefruit.begin();
    Bluefruit.setTxPower(4);

    // Rebranded BLE Device Name
    Bluefruit.setName("LoRaBlue_Gateway");

    bleuart.setRxCallback(bleUartRxCallback);
    bleuart.begin();

    Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
    Bluefruit.Advertising.addTxPower();
    Bluefruit.Advertising.addService(bleuart);
    Bluefruit.ScanResponse.addName();
    Bluefruit.Advertising.addName();

    Bluefruit.Advertising.restartOnDisconnect(true);
    Bluefruit.Advertising.setInterval(32, 244);
    Bluefruit.Advertising.setFastTimeout(30);
    Bluefruit.Advertising.start(0);

    Serial.println(F("[BLE] Advertising as 'LoRaBlue_Gateway'"));
}

void bleUartRxCallback(uint16_t conn_hdl) {
    (void)conn_hdl;
    bleCommandBuffer = bleuart.readString();
    bleCommandBuffer.trim();
    hasNewBleCommand = true;
}

void bleSend(const char *msg) {
    bleuart.print(msg);
    bleuart.flush();
    yield();
}
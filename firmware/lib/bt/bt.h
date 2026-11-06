#ifndef BT_H
#define BT_H

#include <Arduino.h>
#include <bluefruit.h>

extern BLEUart bleuart;

extern volatile bool loraPacketReady;
extern String bleCommandBuffer;
extern volatile bool hasNewBleCommand;
extern unsigned long lastHeartbeat;

void setupBLE(void);
void bleUartRxCallback(uint16_t conn_hdl);
void bleSend(const char *msg);

#endif
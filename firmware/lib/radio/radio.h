#ifndef RADIO_H
#define RADIO_H

#include <Arduino.h>
#include <RadioLib.h>

// lora module pins
#define LORA_NSS D4
#define LORA_DIO1 D1
#define LORA_NRST D2
#define LORA_BUSY D3
#define LORA_RF_SW D5

// Node types — used to interpret type-specific fields (e.g. pump_status
// only makes sense for a cistern). This is orthogonal to node_id, which
// uniquely identifies each physical node so a single Bridge can serve
// any number of nodes of either type (RNF04).
#define DEVICE_TYPE_CISTERN 1
#define DEVICE_TYPE_TANK    2

struct TelemetryData {
    uint8_t node_id;       // unique id per physical node (1-254)
    uint8_t node_type;     // DEVICE_TYPE_CISTERN or DEVICE_TYPE_TANK
    float water_lvl;       // all nodes
    bool pump_status;      // meaningful only when node_type == DEVICE_TYPE_CISTERN
    float bat_percent;     // all nodes
    bool water_valid;      // false: no reliable distance reading (water_lvl is meaningless)
};

extern SX1262 radio;
extern TelemetryData data;

// ------------------------------------
// SHARED
// ------------------------------------
bool enable_radio(void);

// ------------------------------------
// EXCLUSIVE TO TRANSMITTER (TX)
// ------------------------------------
extern unsigned long last_tx_time;
extern const unsigned long tx_interval;
void set_lora_tx_pins(void);
bool send_data(TelemetryData d);

// ------------------------------------
// EXCLUSIVE TO RECEIVER (RX)
// ------------------------------------
extern volatile bool radio_rx_status;
void set_lora_rx_pins(void);
bool receive_data(TelemetryData *d);
void lora_rx_callback(void);

#endif
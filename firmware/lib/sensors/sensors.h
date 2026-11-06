#ifndef SENSORS_H
#define SENSORS_H

#include <Arduino.h>
#include <SparkFun_VL53L1X.h>
#include <Wire.h>

#define PUMP_STATUS_PIN D0

// Water level sampling: collect up to WATER_SAMPLES readings with a good
// VL53L1X range status within WATER_SAMPLE_TIMEOUT_MS and report their
// median. Fewer than WATER_MIN_VALID good samples = invalid reading.
#define WATER_SAMPLES           5
#define WATER_MIN_VALID         3
#define WATER_SAMPLE_TIMEOUT_MS 1500

#define MY_SDA (6u)
#define MY_SCL (7u)

#define BATT_VREF     3.6f
#define BATT_ADC_MAX  4096.0f
#define BATT_DIVIDER_RATIO (1510.0f / 510.0f)
#define BATT_VOLT_EMPTY 3.3f
#define BATT_VOLT_FULL  4.2f

extern TwoWire i2c_conn;
extern SFEVL53L1X distance_sensor;

bool setup_sensors(void);

// Returns true and writes the water-surface distance (m) to *meters, or
// false when the sensor gave too few reliable samples.
bool  get_water_lvl(float *meters);
bool  get_pump_status(void);
float get_battery_percent(void);
float get_battery_voltage(void);

#endif
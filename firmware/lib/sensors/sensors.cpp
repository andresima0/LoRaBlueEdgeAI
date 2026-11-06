#include "sensors.h"

TwoWire i2c_conn(NRF_TWIM1, NRF_TWIS1, SPIM1_SPIS1_TWIM1_TWIS1_SPI1_TWI1_IRQn,
                 MY_SDA, MY_SCL);
SFEVL53L1X distance_sensor(i2c_conn);

bool setup_sensors(void) {

    i2c_conn.begin();
    delay(50);

    if (distance_sensor.begin() != 0)
        return false;

    distance_sensor.setDistanceModeShort();
    distance_sensor.startRanging();

    pinMode(PUMP_STATUS_PIN, INPUT_PULLUP);

    pinMode(PIN_VBAT, INPUT);
    pinMode(VBAT_ENABLE, OUTPUT);
    digitalWrite(VBAT_ENABLE, HIGH);

    return true;
}

bool get_water_lvl(float *meters) {
    uint16_t samples[WATER_SAMPLES];
    uint8_t count = 0;
    const uint32_t start = millis();

    while (count < WATER_SAMPLES && millis() - start < WATER_SAMPLE_TIMEOUT_MS) {
        if (!distance_sensor.checkForDataReady()) {
            delay(5);
            continue;
        }

        uint16_t distance = distance_sensor.getDistance();
        uint8_t status = distance_sensor.getRangeStatus();
        distance_sensor.clearInterrupt();

        // Status 0 = valid measurement; 0 mm means nothing was measured.
        if (status == 0 && distance > 0) {
            samples[count++] = distance;
        }
    }

    if (count < WATER_MIN_VALID)
        return false;

    // Insertion sort (at most WATER_SAMPLES elements), then take the median
    // so a single spurious echo cannot move the reported level.
    for (uint8_t i = 1; i < count; i++) {
        uint16_t key = samples[i];
        int8_t j = i - 1;
        while (j >= 0 && samples[j] > key) {
            samples[j + 1] = samples[j];
            j--;
        }
        samples[j + 1] = key;
    }

    *meters = samples[count / 2] / 1000.0f;
    return true;
}

bool get_pump_status(void) {
    return digitalRead(PUMP_STATUS_PIN) == LOW;
}

float get_battery_voltage(void) {
    analogReference(AR_DEFAULT);
    analogReadResolution(12);

    digitalWrite(VBAT_ENABLE, LOW);
    delay(10);
    uint16_t adcCount = analogRead(PIN_VBAT);
    digitalWrite(VBAT_ENABLE, HIGH);

    analogReference(AR_DEFAULT);
    analogReadResolution(10);

    float adcVoltage = adcCount * BATT_VREF / BATT_ADC_MAX;
    return adcVoltage * BATT_DIVIDER_RATIO;
}

float get_battery_percent(void) {
    float v = get_battery_voltage();
    float pct = (v - BATT_VOLT_EMPTY) / (BATT_VOLT_FULL - BATT_VOLT_EMPTY) * 100.0f;
    return constrain(pct, 0.0f, 100.0f);
}
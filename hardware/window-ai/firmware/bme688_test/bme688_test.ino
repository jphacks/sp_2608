// BME688 動作テスト（Freenove ESP32-WROVER）
// SDA = GPIO21, SCL = GPIO22
// 必要なライブラリ：Adafruit BME680 Library（BME688でも使える）
// シリアルモニタ 115200bps で、1秒ごとに値を表示する

#include <Wire.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_BME680.h>

const int PIN_SDA = 21;
const int PIN_SCL = 22;

Adafruit_BME680 bme(&Wire);

float baseGas = 0;     // 普段の空気のガス抵抗（ゆっくり追従）
bool  baseReady = false;

void scanI2C() {
  Serial.println("I2Cスキャン中...");
  int found = 0;
  for (uint8_t addr = 1; addr < 127; addr++) {
    Wire.beginTransmission(addr);
    if (Wire.endTransmission() == 0) {
      Serial.printf("  見つかった: 0x%02X\n", addr);
      found++;
    }
  }
  if (found == 0) Serial.println("  何も見つからない → 配線（SDA/SCL/電源/抵抗）を確認");
}

void setup() {
  Serial.begin(115200);
  delay(500);
  Wire.begin(PIN_SDA, PIN_SCL);
  scanI2C();

  // Contaモジュールは0x77が初期値。だめなら0x76も試す
  if (!bme.begin(0x77) && !bme.begin(0x76)) {
    Serial.println("BME688が見つからない。配線を確認して再起動してね");
    while (true) delay(1000);
  }
  Serial.println("BME688 OK");

  bme.setTemperatureOversampling(BME680_OS_8X);
  bme.setHumidityOversampling(BME680_OS_2X);
  bme.setPressureOversampling(BME680_OS_4X);
  bme.setIIRFilterSize(BME680_FILTER_SIZE_3);
  bme.setGasHeater(320, 150);  // 320℃で150ms加熱
}

void loop() {
  if (!bme.performReading()) {
    Serial.println("読み取り失敗");
    delay(1000);
    return;
  }

  float gas = bme.gas_resistance / 1000.0;  // kΩ

  // 普段の値をゆっくり追いかける（急な変化は検出用に残す）
  if (!baseReady) { baseGas = gas; baseReady = true; }
  else            { baseGas = baseGas * 0.98 + gas * 0.02; }

  float drop = (baseGas - gas) / baseGas * 100.0;  // 普段より何%下がったか

  Serial.printf("温度 %.1f℃  湿度 %.1f%%  気圧 %.1fhPa  ガス %.1fkΩ  （普段比 -%.0f%%）",
                bme.temperature, bme.humidity, bme.pressure / 100.0, gas, drop < 0 ? 0 : drop);
  if (drop > 20) Serial.print("  ← におい検知！");
  Serial.println();

  delay(1000);
}

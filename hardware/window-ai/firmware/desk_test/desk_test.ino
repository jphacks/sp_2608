// 机上テスト：リレーモジュール＋BME688（Freenove ESP32-WROVER）
// 起動すると
//   1) I2Cスキャン → BME688の確認
//   2) モジュールA（GPIO25）→ B（GPIO26）を0.5秒ずつ自動でカチッ
//   3) 1秒ごとにBME688の値を表示
//   4) においを検知したら（普段比 -20%以上）モジュールBをカチッ（暖機なし・机用）
// シリアルモニタ（115200）から u=A / d=B / s=停止 / t=自動テストをもう一度

#include <Wire.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_BME680.h>

const int PIN_A = 25;    // モジュールA（上げ）
const int PIN_B = 26;    // モジュールB（下げ）
const int PIN_SDA = 21;
const int PIN_SCL = 22;
const unsigned long ON_MS = 500;
const float DETECT_DROP = 20.0;
const unsigned long COOLDOWN_MS = 10000;  // 机用：10秒は再検知しない

Adafruit_BME680 bme(&Wire);
bool bmeOK = false;
float baseGas = 0;
bool baseReady = false;
unsigned long lastRead = 0;
unsigned long lastDetect = 0;
bool everDetected = false;

void allOff() {
  digitalWrite(PIN_A, LOW);
  digitalWrite(PIN_B, LOW);
}

void pulse(int pin, const char* name) {
  allOff();
  delay(50);
  Serial.printf(">> %s ON\n", name);
  digitalWrite(pin, HIGH);
  delay(ON_MS);
  allOff();
  Serial.printf(">> %s OFF\n", name);
}

void relaySelfTest() {
  Serial.println("--- リレー自動テスト ---");
  pulse(PIN_A, "A (GPIO25)");
  delay(1000);
  pulse(PIN_B, "B (GPIO26)");
  Serial.println("--- A→Bの順にカチッと鳴ればOK ---");
}

void setup() {
  pinMode(PIN_A, OUTPUT);
  pinMode(PIN_B, OUTPUT);
  allOff();
  Serial.begin(115200);
  delay(500);
  Serial.println();
  Serial.println("=== desk test start ===");

  Wire.begin(PIN_SDA, PIN_SCL);
  Serial.println("I2Cスキャン中...");
  int found = 0;
  for (uint8_t addr = 1; addr < 127; addr++) {
    Wire.beginTransmission(addr);
    if (Wire.endTransmission() == 0) {
      Serial.printf("  見つかった: 0x%02X\n", addr);
      found++;
    }
  }
  if (found == 0) Serial.println("  何も見つからない → BME688の配線を確認");

  if (bme.begin(0x77) || bme.begin(0x76)) {
    bmeOK = true;
    bme.setTemperatureOversampling(BME680_OS_8X);
    bme.setHumidityOversampling(BME680_OS_2X);
    bme.setPressureOversampling(BME680_OS_4X);
    bme.setIIRFilterSize(BME680_FILTER_SIZE_3);
    bme.setGasHeater(320, 150);
    Serial.println("BME688 OK");
  } else {
    Serial.println("BME688 NG（リレーのテストだけ続ける）");
  }

  relaySelfTest();
  Serial.println("u=A / d=B / s=停止 / t=自動テスト");
}

void loop() {
  if (Serial.available()) {
    char c = Serial.read();
    if (c == 'u') pulse(PIN_A, "A (GPIO25)");
    else if (c == 'd') pulse(PIN_B, "B (GPIO26)");
    else if (c == 's') { allOff(); Serial.println("STOP"); }
    else if (c == 't') relaySelfTest();
  }

  if (!bmeOK || millis() - lastRead < 1000) return;
  lastRead = millis();
  if (!bme.performReading()) { Serial.println("読み取り失敗"); return; }

  float gas = bme.gas_resistance / 1000.0;
  if (!baseReady) { baseGas = gas; baseReady = true; }
  float drop = (baseGas - gas) / baseGas * 100.0;
  if (drop < DETECT_DROP) baseGas = baseGas * 0.98 + gas * 0.02;

  Serial.printf("温度 %.1f℃  湿度 %.1f%%  ガス %.1fkΩ  普段比 -%.0f%%\n",
                bme.temperature, bme.humidity, gas, drop < 0 ? 0 : drop);

  bool cool = !everDetected || millis() - lastDetect > COOLDOWN_MS;
  if (drop > DETECT_DROP && cool) {
    Serial.println("におい検知！ → モジュールBをカチッ");
    pulse(PIN_B, "B (GPIO26)");
    lastDetect = millis();
    everDetected = true;
  }
}

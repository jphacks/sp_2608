// ステップワゴン 窓AI化 車載版 v1（Freenove ESP32-WROVER）
// ・においを検知したら、窓を少しだけ開ける（下げリレーを OPEN_MS だけON）
// ・閉めるのは自動ではやらない（挟み込み防止）。純正スイッチかシリアルの u で閉める
// ・シリアルモニタ（115200）から手動操作もできる： u=上げ / d=下げ / s=停止
// ・上げと下げが逆だったら SWAP_UPDOWN を true にして書き込み直す
// 必要なライブラリ：Adafruit BME680

#include <Wire.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_BME680.h>

// ---- 設定 ----
const bool SWAP_UPDOWN = false;          // 上げ下げが逆なら true
const unsigned long MANUAL_MS = 500;      // u / d で動かす時間（最初は短く）
const unsigned long OPEN_MS = 1500;       // におい検知で窓を開ける時間
const unsigned long MAX_ON_MS = 4000;     // どんな場合もこれ以上はONにしない
const float DETECT_DROP = 20.0;           // 普段より何%ガスが下がったら検知か
const unsigned long WARMUP_MS = 300000;   // 起動から5分は検知しない（センサーの暖機）
const unsigned long COOLDOWN_MS = 60000;  // 一度開けたら60秒は再検知しない

// ---- ピン ----
const int PIN_A = 25;  // モジュールA
const int PIN_B = 26;  // モジュールB
const int PIN_SDA = 21;
const int PIN_SCL = 22;
int PIN_UP, PIN_DOWN;

Adafruit_BME680 bme(&Wire);
bool bmeOK = false;
float baseGas = 0;
bool baseReady = false;
unsigned long lastOpen = 0;
bool everOpened = false;

void allOff() {
  digitalWrite(PIN_A, LOW);
  digitalWrite(PIN_B, LOW);
}

// 片方だけをONにする（もう片方は必ずOFF）
void pulse(int pin, const char* name, unsigned long ms) {
  if (ms > MAX_ON_MS) ms = MAX_ON_MS;
  allOff();
  delay(50);
  Serial.printf("%s ON (%lu ms)\n", name, ms);
  digitalWrite(pin, HIGH);
  unsigned long start = millis();
  while (millis() - start < ms) {
    if (Serial.available() && Serial.peek() == 's') break;  // s で途中停止
    delay(5);
  }
  allOff();
  Serial.printf("%s OFF\n", name);
}

void setup() {
  pinMode(PIN_A, OUTPUT);
  pinMode(PIN_B, OUTPUT);
  allOff();  // 起動直後に必ずOFF

  PIN_UP   = SWAP_UPDOWN ? PIN_B : PIN_A;
  PIN_DOWN = SWAP_UPDOWN ? PIN_A : PIN_B;

  Serial.begin(115200);
  delay(500);
  Serial.println("car_v1 start: u=上げ / d=下げ / s=停止");

  Wire.begin(PIN_SDA, PIN_SCL);
  if (bme.begin(0x77) || bme.begin(0x76)) {
    bmeOK = true;
    bme.setTemperatureOversampling(BME680_OS_8X);
    bme.setHumidityOversampling(BME680_OS_2X);
    bme.setPressureOversampling(BME680_OS_4X);
    bme.setIIRFilterSize(BME680_FILTER_SIZE_3);
    bme.setGasHeater(320, 150);
    Serial.println("BME688 OK（5分暖機してから検知開始）");
  } else {
    Serial.println("BME688が見つからない → 手動操作だけ使える");
  }
}

unsigned long lastRead = 0;

void loop() {
  // 手動操作
  if (Serial.available()) {
    char c = Serial.read();
    if (c == 'u') pulse(PIN_UP, "UP", MANUAL_MS);
    else if (c == 'd') pulse(PIN_DOWN, "DOWN", MANUAL_MS);
    else if (c == 's') { allOff(); Serial.println("STOP"); }
  }

  // 1秒ごとにセンサーを読む
  if (!bmeOK || millis() - lastRead < 1000) return;
  lastRead = millis();
  if (!bme.performReading()) return;

  float gas = bme.gas_resistance / 1000.0;
  if (!baseReady) { baseGas = gas; baseReady = true; }
  float drop = (baseGas - gas) / baseGas * 100.0;

  // においがない時だけ「普段の値」を更新（におい中に基準が引きずられないように）
  if (drop < DETECT_DROP) baseGas = baseGas * 0.98 + gas * 0.02;

  bool warm = millis() > WARMUP_MS;
  bool cool = !everOpened || millis() - lastOpen > COOLDOWN_MS;

  Serial.printf("ガス %.1fkΩ 普段比 -%.0f%% %s\n", gas, drop < 0 ? 0 : drop,
                warm ? "" : "(暖機中)");

  if (warm && cool && drop > DETECT_DROP) {
    Serial.println("におい検知 → 窓を少し開ける");
    pulse(PIN_DOWN, "DOWN(auto)", OPEN_MS);
    lastOpen = millis();
    everOpened = true;
  }
}

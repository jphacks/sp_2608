// ステップワゴン 窓AI化 車載版 v2（OTA対応） Freenove ESP32-WROVER
// car_v1 の機能に加えて
//   ・Wi-Fi経由でプログラムを書き込める（OTA）
//   ・Wi-Fi経由でログを見たり、u / d / s / i を送れる（ポート23）
// Wi-Fiは、まずテザリング（WIFI_SSID）につなぎに行き、つながらなければ
// ESP32が自分でWi-Fi（AP_SSID）を出す。
// 必要なライブラリ：Adafruit BME680（WiFi / ESPmDNS / ArduinoOTA はESP32に最初から入っている）

#include <stdarg.h>
#include <WiFi.h>
#include <ESPmDNS.h>
#include <ArduinoOTA.h>
#include <Wire.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_BME680.h>

#if __has_include("secrets.h")
#include "secrets.h"
#else
#error "secrets.h がありません。secrets.example.h をコピーして作ってください。"
#endif

// ===== ここを自分の環境に合わせて書き換える =====
const char* VERSION   = "v2.1";                    // 書き込むたびに変えると、更新されたか確認しやすい
const char* WIFI_SSID = SECRET_WIFI_SSID;        // secrets.h に書く（リポジトリには入らない）
const char* WIFI_PASS = SECRET_WIFI_PASS;        // secrets.h に書く（リポジトリには入らない）
const char* AP_SSID   = "stepwgn-window";          // テザリングにつながらないときにESP32が出すWi-Fi
const char* AP_PASS   = "window2026";              // 8文字以上
const char* HOSTNAME  = "stepwgn-window";          // ネットワーク上の名前
const char* OTA_PASS  = "window2026";              // OTA書き込み時に聞かれるパスワード

const bool SWAP_UPDOWN = false;          // u で窓が下がったら true にする
const unsigned long MANUAL_MS   = 500;    // u / d で動かす時間
const unsigned long OPEN_MS     = 1500;   // におい検知で窓を開ける時間
const unsigned long MAX_ON_MS   = 4000;   // どんな場合もこれ以上はONにしない
const float         DETECT_DROP = 20.0;   // 普段より何%ガスが下がったら検知か
const unsigned long WARMUP_MS   = 300000; // 起動から5分は検知しない
const unsigned long COOLDOWN_MS = 60000;  // 一度開けたら60秒は再検知しない
// ================================================

const int PIN_A = 25;  // モジュールA
const int PIN_B = 26;  // モジュールB
const int PIN_SDA = 21;
const int PIN_SCL = 22;
int PIN_UP, PIN_DOWN;

Adafruit_BME680 bme(&Wire);
bool bmeOK = false;
float baseGas = 0;
bool baseReady = false;
unsigned long lastRead = 0;
unsigned long lastOpen = 0;
bool everOpened = false;
bool otaRunning = false;

WiFiServer logServer(23);
WiFiClient logClient;

// シリアルとWi-Fiログの両方に出す
void logf(const char* fmt, ...) {
  char buf[256];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, sizeof(buf), fmt, ap);
  va_end(ap);
  Serial.print(buf);
  if (logClient && logClient.connected()) logClient.print(buf);
}

int readCmd() {
  if (Serial.available()) return Serial.read();
  if (logClient && logClient.connected() && logClient.available()) return logClient.read();
  return -1;
}

bool stopRequested() {
  if (Serial.available() && Serial.peek() == 's') return true;
  if (logClient && logClient.connected() && logClient.available() && logClient.peek() == 's') return true;
  return false;
}

void allOff() {
  digitalWrite(PIN_A, LOW);
  digitalWrite(PIN_B, LOW);
}

// 片方だけをONにする（もう片方は必ずOFF）
void pulse(int pin, const char* name, unsigned long ms) {
  if (ms > MAX_ON_MS) ms = MAX_ON_MS;
  allOff();
  delay(50);
  logf("%s ON (%lu ms)\n", name, ms);
  digitalWrite(pin, HIGH);
  unsigned long start = millis();
  while (millis() - start < ms) {
    if (stopRequested()) break;
    delay(5);
  }
  allOff();
  logf("%s OFF\n", name);
}

void printInfo() {
  logf("--- %s %s ---\n", HOSTNAME, VERSION);
  if (WiFi.getMode() == WIFI_AP) {
    logf("Wi-Fi: ESP32のアクセスポイント %s / IP %s\n", AP_SSID, WiFi.softAPIP().toString().c_str());
  } else {
    logf("Wi-Fi: %s に接続 / IP %s\n", WIFI_SSID, WiFi.localIP().toString().c_str());
  }
  logf("BME688: %s / 起動から %lu 秒 / %s\n", bmeOK ? "OK" : "NG", millis() / 1000,
       millis() > WARMUP_MS ? "検知中" : "暖機中");
  logf("コマンド: u=上げ d=下げ s=停止 i=情報\n");
}

void setupWiFi() {
  WiFi.mode(WIFI_STA);
  WiFi.setHostname(HOSTNAME);
  // iPhoneテザリング内でIPを固定（再起動してもIPが変わらないように）
  IPAddress ip(172, 20, 10, 7), gw(172, 20, 10, 1), mask(255, 255, 255, 240);
  WiFi.config(ip, gw, mask, gw);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  logf("Wi-Fi %s に接続中", WIFI_SSID);
  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 15000) {
    delay(500);
    logf(".");
  }
  logf("\n");
  if (WiFi.status() == WL_CONNECTED) {
    logf("接続OK  IP: %s\n", WiFi.localIP().toString().c_str());
  } else {
    WiFi.disconnect(true);
    WiFi.mode(WIFI_AP);
    WiFi.softAP(AP_SSID, AP_PASS);
    logf("テザリングにつながらないので、アクセスポイント %s を出しました  IP: %s\n",
         AP_SSID, WiFi.softAPIP().toString().c_str());
  }
}

void setupOTA() {
  ArduinoOTA.setHostname(HOSTNAME);
  ArduinoOTA.setPassword(OTA_PASS);
  ArduinoOTA.onStart([]() {
    otaRunning = true;
    allOff();
    logf("OTA書き込み開始（リレーは全部OFF）\n");
  });
  ArduinoOTA.onEnd([]() {
    logf("OTA書き込み完了。再起動します\n");
  });
  ArduinoOTA.onError([](ota_error_t e) {
    otaRunning = false;
    logf("OTAエラー: %u\n", (unsigned)e);
  });
  ArduinoOTA.begin();
  MDNS.addService("telnet", "tcp", 23);
}

void handleLogClient() {
  if (logServer.hasClient()) {
    if (logClient) logClient.stop();
    logClient = logServer.available();
    printInfo();
  }
}

void setup() {
  pinMode(PIN_A, OUTPUT);
  pinMode(PIN_B, OUTPUT);
  allOff();  // 起動直後に必ずOFF

  PIN_UP   = SWAP_UPDOWN ? PIN_B : PIN_A;
  PIN_DOWN = SWAP_UPDOWN ? PIN_A : PIN_B;

  Serial.begin(115200);
  delay(500);
  logf("\n=== car_v2_ota %s start ===\n", VERSION);

  setupWiFi();
  setupOTA();
  logServer.begin();
  logServer.setNoDelay(true);

  Wire.begin(PIN_SDA, PIN_SCL);
  if (bme.begin(0x77) || bme.begin(0x76)) {
    bmeOK = true;
    bme.setTemperatureOversampling(BME680_OS_8X);
    bme.setHumidityOversampling(BME680_OS_2X);
    bme.setPressureOversampling(BME680_OS_4X);
    bme.setIIRFilterSize(BME680_FILTER_SIZE_3);
    bme.setGasHeater(320, 150);
  }
  printInfo();
}

void loop() {
  ArduinoOTA.handle();
  handleLogClient();
  if (otaRunning) return;  // 書き込み中はリレーもセンサーも触らない

  int c = readCmd();
  if (c == 'u') pulse(PIN_UP, "UP", MANUAL_MS);
  else if (c == 'd') pulse(PIN_DOWN, "DOWN", MANUAL_MS);
  else if (c == 's') { allOff(); logf("STOP\n"); }
  else if (c == 'i') printInfo();

  if (!bmeOK || millis() - lastRead < 1000) return;
  lastRead = millis();
  if (!bme.performReading()) return;

  float gas = bme.gas_resistance / 1000.0;
  if (!baseReady) { baseGas = gas; baseReady = true; }
  float drop = (baseGas - gas) / baseGas * 100.0;
  if (drop < DETECT_DROP) baseGas = baseGas * 0.98 + gas * 0.02;

  bool warm = millis() > WARMUP_MS;
  bool cool = !everOpened || millis() - lastOpen > COOLDOWN_MS;

  logf("ガス %.1fkΩ 普段比 -%.0f%% 湿度 %.1f%% %s\n", gas, drop < 0 ? 0 : drop,
       bme.humidity, warm ? "" : "(暖機中)");

  if (warm && cool && drop > DETECT_DROP) {
    logf("におい検知 → 窓を少し開ける\n");
    pulse(PIN_DOWN, "DOWN(auto)", OPEN_MS);
    lastOpen = millis();
    everOpened = true;
  }
}

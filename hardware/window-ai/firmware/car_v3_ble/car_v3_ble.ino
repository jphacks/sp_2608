// ステップワゴン 窓AI化 車載版 v3（BLE対応） Freenove ESP32-WROVER
// car_v2_ota との違い
//   ・アプリとの通信を Wi-Fi の TCP から BLE に移した。起動時に Wi-Fi は上げない
//   ・OTA は必要なときだけ。BLE か シリアルで o を送ると、5分だけ Wi-Fi と OTA を立てる
//   ・BLE の notify でログを送る。MTU を超える行は分割して送り、受け側で組み立て直す
// 移行の理由：Android 端末の Wi-Fi を機器に占有させると、同じ端末でインターネットが使えなくなる。
// BLE にすると Wi-Fi が空くので、窓の操作と地図・音楽を同時に成立させられる。
//
// 必要なライブラリ：Adafruit BME680
//   （WiFi / ESPmDNS / ArduinoOTA / BLE はESP32のコアに最初から入っている。追加インストールは不要）
//
// **Arduino IDE の設定**：ツール → Partition Scheme を
//   「Minimal SPIFFS (1.9MB APP with OTA/190KB SPIFFS)」にする。以降のOTAも同じ設定でビルドする。
//   実測 1,762,419 バイト。既定のパーティションはアプリ領域が 1,310,720 バイトなので入らない（134%）。
//   Minimal SPIFFS なら 1,966,080 バイトなので収まる（約90%）。
//   「Huge APP」は OTA 用の領域が無いので選んではいけない。
//   パーティションの変更はOTAではできないので、v2 から v3 への更新は一度だけUSBで書き込む。
//   v3 になったあとは o コマンドでOTAを立てられるので、USBは不要に戻る。

#include <stdarg.h>
#include <WiFi.h>
#include <ESPmDNS.h>
#include <ArduinoOTA.h>
#include <Wire.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_BME680.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#if __has_include("secrets.h")
#include "secrets.h"
#else
#error "secrets.h がありません。secrets.example.h をコピーして作ってください。"
#endif

// ===== ここを自分の環境に合わせて書き換える =====
const char* VERSION   = "v3.0";                    // 書き込むたびに変えると、更新されたか確認しやすい
const char* WIFI_SSID = SECRET_WIFI_SSID;        // secrets.h に書く（リポジトリには入らない）
const char* WIFI_PASS = SECRET_WIFI_PASS;        // secrets.h に書く（リポジトリには入らない）
const char* AP_SSID   = "stepwgn-window";          // テザリングにつながらないときにESP32が出すWi-Fi
const char* AP_PASS   = "window2026";              // 8文字以上
const char* HOSTNAME  = "stepwgn-window";          // ネットワーク上とBLEの名前
const char* OTA_PASS  = "window2026";              // OTA書き込み時に聞かれるパスワード

const bool SWAP_UPDOWN = false;          // u で窓が下がったら true にする
const unsigned long MANUAL_MS   = 500;    // u / d で動かす時間
const unsigned long OPEN_MS     = 1500;   // におい検知で窓を開ける時間
const unsigned long MAX_ON_MS   = 4000;   // どんな場合もこれ以上はONにしない
const float         DETECT_DROP = 20.0;   // 普段より何%ガスが下がったら検知か
const unsigned long WARMUP_MS   = 300000; // 起動から5分は検知しない
const unsigned long COOLDOWN_MS = 60000;  // 一度開けたら60秒は再検知しない
const unsigned long OTA_WINDOW_MS = 300000; // o を送ってから何ミリ秒OTAを受け付けるか（5分）
// ================================================

// BLE の口。アプリ側（WindowConfiguration.kt）と同じ値でなければつながらない。
#define SERVICE_UUID "6e6b1f00-5f3a-4b7c-9d21-8c4f1a2b3c01"
#define CMD_UUID     "6e6b1f01-5f3a-4b7c-9d21-8c4f1a2b3c01"
#define LOG_UUID     "6e6b1f02-5f3a-4b7c-9d21-8c4f1a2b3c01"

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

// Wi-Fi と OTA は o を送られたときだけ立てる
bool wifiUp = false;
bool otaInitialised = false;
unsigned long otaStarted = 0;

WiFiServer logServer(23);
WiFiClient logClient;

BLEServer* bleServer = nullptr;
BLECharacteristic* logChar = nullptr;
volatile bool bleConnected = false;
volatile uint16_t bleConnId = 0;

// BLE のコールバックは別のタスクで動くので、受け取ったコマンドはリングバッファに置いて
// loop() 側で取り出す。書く側と読む側が1つずつなので、これで取りこぼさない。
const int CMD_BUF = 32;
volatile char cmdBuf[CMD_BUF];
volatile int cmdHead = 0;
volatile int cmdTail = 0;

void cmdPush(char c) {
  int next = (cmdHead + 1) % CMD_BUF;
  if (next != cmdTail) {       // 溢れたら捨てる。古い指示を溜めるより捨てるほうが安全
    cmdBuf[cmdHead] = c;
    cmdHead = next;
  }
}

int cmdPop() {
  if (cmdHead == cmdTail) return -1;
  char c = cmdBuf[cmdTail];
  cmdTail = (cmdTail + 1) % CMD_BUF;
  return c;
}

int cmdPeek() {
  if (cmdHead == cmdTail) return -1;
  return cmdBuf[cmdTail];
}

// notify で一度に送れる大きさは MTU-3。アプリ側が行に組み立て直すので、分割位置は気にしなくてよい。
size_t bleChunkSize() {
  if (bleServer == nullptr) return 20;
  uint16_t mtu = bleServer->getPeerMTU(bleConnId);
  if (mtu < 23) return 20;
  size_t chunk = mtu - 3;
  return chunk > 180 ? 180 : chunk;
}

void bleNotify(const char* text) {
  if (!bleConnected || logChar == nullptr) return;
  size_t len = strlen(text);
  size_t chunk = bleChunkSize();
  for (size_t sent = 0; sent < len; sent += chunk) {
    size_t n = len - sent;
    if (n > chunk) n = chunk;
    logChar->setValue((uint8_t*)(text + sent), n);
    logChar->notify();
    delay(8);   // 続けて notify すると送信バッファが溢れるので少し待つ
  }
}

// シリアル・Wi-Fiログ・BLE のすべてに出す
void logf(const char* fmt, ...) {
  char buf[256];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, sizeof(buf), fmt, ap);
  va_end(ap);
  Serial.print(buf);
  if (logClient && logClient.connected()) logClient.print(buf);
  bleNotify(buf);
}

int readCmd() {
  if (Serial.available()) return Serial.read();
  if (logClient && logClient.connected() && logClient.available()) return logClient.read();
  return cmdPop();
}

bool stopRequested() {
  if (Serial.available() && Serial.peek() == 's') return true;
  if (logClient && logClient.connected() && logClient.available() && logClient.peek() == 's') return true;
  if (cmdPeek() == 's') return true;
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
  logf("BLE: %s\n", bleConnected ? "接続中" : "待ち受け中");
  if (!wifiUp) {
    logf("Wi-Fi: 停止中（o でOTA受付を開始）\n");
  } else if (WiFi.getMode() == WIFI_AP) {
    logf("Wi-Fi: ESP32のアクセスポイント %s / IP %s\n", AP_SSID, WiFi.softAPIP().toString().c_str());
  } else {
    logf("Wi-Fi: %s に接続 / IP %s\n", WIFI_SSID, WiFi.localIP().toString().c_str());
  }
  logf("BME688: %s / 起動から %lu 秒 / %s\n", bmeOK ? "OK" : "NG", millis() / 1000,
       millis() > WARMUP_MS ? "検知中" : "暖機中");
  logf("コマンド: u=上げ d=下げ s=停止 i=情報 o=OTA受付\n");
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

// o を受け取ったときだけ Wi-Fi と OTA を立てる。立てっぱなしにしない。
void startOtaWindow() {
  if (!wifiUp) {
    setupWiFi();
    if (!otaInitialised) {
      setupOTA();
      otaInitialised = true;
    } else {
      ArduinoOTA.begin();   // 2回目以降はコールバックの登録をやり直さない
    }
    logServer.begin();
    logServer.setNoDelay(true);
    wifiUp = true;
  }
  otaStarted = millis();
  logf("OTA受付を開始しました（%lu 分で自動的に切ります）\n", OTA_WINDOW_MS / 60000);
  printInfo();
}

void stopOtaWindow() {
  logf("OTA受付を終了してWi-Fiを切ります\n");
  if (logClient) logClient.stop();
  logServer.end();
  ArduinoOTA.end();
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
  wifiUp = false;
}

void handleLogClient() {
  if (logServer.hasClient()) {
    if (logClient) logClient.stop();
    logClient = logServer.available();
    printInfo();
  }
}

class WindowServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* server, esp_ble_gatts_cb_param_t* param) override {
    bleConnId = param->connect.conn_id;
    bleConnected = true;
    Serial.println("BLE: アプリがつながりました");
  }
  void onDisconnect(BLEServer* server) override {
    bleConnected = false;
    Serial.println("BLE: 切れました。待ち受けに戻ります");
    server->startAdvertising();   // 次の接続を受けられるように出し直す
  }
};

class WindowCmdCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) override {
    // getValue() の戻り値の型はESP32コアの版で変わる（std::string と String）。
    // getData() と getLength() はどちらの版にもあるので、こちらを使う。
    uint8_t* data = characteristic->getData();
    size_t length = characteristic->getLength();
    for (size_t i = 0; i < length; i++) cmdPush((char)data[i]);
  }
};

void setupBle() {
  BLEDevice::init(HOSTNAME);
  BLEDevice::setMTU(185);   // 行が1回の notify で収まりやすくなる（収まらなくても受け側で組み立て直す）

  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new WindowServerCallbacks());

  BLEService* service = bleServer->createService(SERVICE_UUID);

  BLECharacteristic* cmdChar = service->createCharacteristic(
      CMD_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  cmdChar->setCallbacks(new WindowCmdCallbacks());

  logChar = service->createCharacteristic(LOG_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  logChar->addDescriptor(new BLE2902());

  service->start();

  // 広告にはサービスUUIDだけを載せる。128bitのUUIDと名前を両方載せると31バイトに収まらないので、
  // 名前はスキャン応答のほうに回す。アプリはサービスUUIDで絞り込む。
  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMaxPreferred(0x12);
  BLEDevice::startAdvertising();
  Serial.println("BLE: 待ち受けを始めました");
}

void setup() {
  pinMode(PIN_A, OUTPUT);
  pinMode(PIN_B, OUTPUT);
  allOff();  // 起動直後に必ずOFF

  PIN_UP   = SWAP_UPDOWN ? PIN_B : PIN_A;
  PIN_DOWN = SWAP_UPDOWN ? PIN_A : PIN_B;

  Serial.begin(115200);
  delay(500);
  logf("\n=== car_v3_ble %s start ===\n", VERSION);

  // Wi-Fi は立てない。v2 はここで最大15秒待っていたが、BLE はすぐ待ち受けに入れる。
  setupBle();

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
  if (wifiUp) {
    ArduinoOTA.handle();
    handleLogClient();
    if (!otaRunning && millis() - otaStarted > OTA_WINDOW_MS) stopOtaWindow();
  }
  if (otaRunning) return;  // 書き込み中はリレーもセンサーも触らない

  int c = readCmd();
  if (c == 'u') pulse(PIN_UP, "UP", MANUAL_MS);
  else if (c == 'd') pulse(PIN_DOWN, "DOWN", MANUAL_MS);
  else if (c == 's') { allOff(); logf("STOP\n"); }
  else if (c == 'i') printInfo();
  else if (c == 'o') startOtaWindow();

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
    // EVENT 行は機械が読む用。人が読む行は従来のまま残す。
    logf("EVENT smell drop=%.0f\n", drop);
    logf("におい検知 → 窓を少し開ける\n");
    pulse(PIN_DOWN, "DOWN(auto)", OPEN_MS);
    lastOpen = millis();
    everOpened = true;
  }
}

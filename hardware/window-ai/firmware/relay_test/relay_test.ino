// リレーモジュール 机上テスト用（Freenove ESP32-WROVER）
// GPIO25 → モジュールA（上げ）IN
// GPIO26 → モジュールB（下げ）IN
// シリアルモニタ（115200bps）で u / d / s を送ると動く
//   u : 上げリレーを MAX_ON_MS だけON
//   d : 下げリレーを MAX_ON_MS だけON
//   s : 両方すぐOFF

const int PIN_UP   = 25;
const int PIN_DOWN = 26;

// 1回にONにする最大時間（ミリ秒）。最初は短く。
const unsigned long MAX_ON_MS = 500;

void allOff() {
  digitalWrite(PIN_UP, LOW);
  digitalWrite(PIN_DOWN, LOW);
}

// 片方をONにするときは、必ずもう片方をOFFにしてから
void pulse(int pin, const char* name) {
  allOff();
  delay(50);
  Serial.printf("%s ON (%lu ms)\n", name, MAX_ON_MS);
  digitalWrite(pin, HIGH);
  unsigned long start = millis();
  while (millis() - start < MAX_ON_MS) {
    if (Serial.available() && Serial.peek() == 's') break;  // s で途中停止
    delay(5);
  }
  allOff();
  Serial.printf("%s OFF\n", name);
}

void setup() {
  pinMode(PIN_UP, OUTPUT);
  pinMode(PIN_DOWN, OUTPUT);
  allOff();
  Serial.begin(115200);
  delay(300);
  Serial.println("relay test ready: u=上げ / d=下げ / s=停止");
}

void loop() {
  if (!Serial.available()) return;
  char c = Serial.read();
  if (c == 'u') pulse(PIN_UP, "UP");
  else if (c == 'd') pulse(PIN_DOWN, "DOWN");
  else if (c == 's') { allOff(); Serial.println("STOP"); }
}

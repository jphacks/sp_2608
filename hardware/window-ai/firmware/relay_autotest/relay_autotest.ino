// リレーモジュール 自動テスト（入力不要）
// 書き込むだけで、次をずっと繰り返す
//   モジュールA（GPIO25・上げ）を0.5秒ON → 2秒休み
//   モジュールB（GPIO26・下げ）を0.5秒ON → 3秒休み
// シリアルモニタ（115200bps）を開くと、今どっちをONにしたか表示される

const int PIN_UP   = 25;
const int PIN_DOWN = 26;
const unsigned long ON_MS = 500;

void allOff() {
  digitalWrite(PIN_UP, LOW);
  digitalWrite(PIN_DOWN, LOW);
}

void pulse(int pin, const char* name) {
  allOff();
  delay(50);
  Serial.printf("%s ON\n", name);
  digitalWrite(pin, HIGH);
  delay(ON_MS);
  allOff();
  Serial.printf("%s OFF\n", name);
}

void setup() {
  pinMode(PIN_UP, OUTPUT);
  pinMode(PIN_DOWN, OUTPUT);
  allOff();
  Serial.begin(115200);
  delay(500);
  Serial.println("auto relay test start");
}

void loop() {
  pulse(PIN_UP, "A (UP, GPIO25)");
  delay(2000);
  pulse(PIN_DOWN, "B (DOWN, GPIO26)");
  delay(3000);
}

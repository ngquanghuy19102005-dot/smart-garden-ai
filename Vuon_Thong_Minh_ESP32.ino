#include <WiFi.h>
#include <PubSubClient.h>
#include <DHT.h>
#include <LiquidCrystal_I2C.h>

// ===== WIFI =====
const char* ssid = "Huy";
const char* password = "01234567899";

// ===== MQTT =====
const char* mqtt_server = "10.32.87.198";

// ===== CHÂN =====
#define SOIL_PIN 34
#define RELAY_PIN 27
#define DHT_PIN 4
#define DHTTYPE DHT11

// ===== THIẾT BỊ =====
DHT dht(DHT_PIN, DHTTYPE);
LiquidCrystal_I2C lcd(0x27, 16, 2);

WiFiClient espClient;
PubSubClient client(espClient);

// ===== NGƯỠNG =====
int soilDry = 3500 ;   // KHÔ
int soilWet = 2500 ;   // ƯỚT

// ===== MODE =====
bool autoMode = true;     // mặc định AUTO
bool manualPump = false;  // trạng thái bơm khi MANUAL

// ===== WIFI =====
void setup_wifi() {
  Serial.print("Dang ket noi WiFi...");
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nDa ket noi WiFi!");
}

// ===== MQTT CALLBACK =====
void callback(char* topic, byte* payload, unsigned int length) {
  String message = "";
  for (int i = 0; i < length; i++) {
    message += (char)payload[i];
  }

  String t = String(topic);

  // ===== AUTO MODE =====
  if (t == "garden/esp32/automode") {
    if (message == "ON") {
      autoMode = true;
      Serial.println("AUTO MODE ON");
    } else {
      autoMode = false;
      Serial.println("MANUAL MODE");
    }
  }

  // ===== PUMP MANUAL =====
  if (t == "garden/esp32/pump") {
    if (!autoMode) {
      if (message == "ON") {
        manualPump = true;
        Serial.println("MANUAL: BAT BOM");
      } else {
        manualPump = false;
        Serial.println("MANUAL: TAT BOM");
      }
    }
  }
}

// ===== MQTT CONNECT =====
void reconnect() {
  while (!client.connected()) {
    Serial.print("Dang ket noi MQTT...");
    if (client.connect("ESP32_Client")) {
      Serial.println("OK");

      // SUBSCRIBE ĐỂ NHẬN LỆNH TỪ APP
      client.subscribe("garden/esp32/pump");
      client.subscribe("garden/esp32/automode");

    } else {
      Serial.print("Loi, rc=");
      Serial.print(client.state());
      Serial.println(" thu lai...");
      delay(2000);
    }
  }
}

void setup() {
  Serial.begin(115200);

  pinMode(RELAY_PIN, OUTPUT);
  digitalWrite(RELAY_PIN, HIGH); // tắt bơm

  dht.begin();

  lcd.init();
  lcd.backlight();

  setup_wifi();

  client.setServer(mqtt_server, 1883);
  client.setCallback(callback); // 🔥 QUAN TRỌNG
}

void loop() {
  if (!client.connected()) reconnect();
  client.loop();

  // ===== ĐỌC CẢM BIẾN =====
  int soilValue = analogRead(SOIL_PIN);
  float temp = dht.readTemperature();
  float hum = dht.readHumidity();

  if (isnan(temp) || isnan(hum)) {
    Serial.println("Loi doc DHT!");
    return;
  }

  // ===== DEBUG =====
  Serial.print("Soil: "); Serial.print(soilValue);
  Serial.print(" | Temp: "); Serial.print(temp);
  Serial.print(" | Hum: "); Serial.println(hum);

  // ===== LCD =====
  lcd.setCursor(0, 0);
  lcd.print("T:");
  lcd.print(temp);
  lcd.print("C H:");
  lcd.print(hum);
  lcd.print("   ");

  lcd.setCursor(0, 1);
  lcd.print("Soil:");
  lcd.print(soilValue);
  lcd.print("   ");

  // ===== LOGIC AUTO / MANUAL =====
  if (autoMode) {
    // ===== AUTO =====
    if (soilValue > soilDry) {
      digitalWrite(RELAY_PIN, HIGH);  // bật bơm
      Serial.println("AUTO: Dat KHO -> BAT BOM");
    } 
    else if (soilValue < soilWet) {
      digitalWrite(RELAY_PIN, LOW); // tắt bơm
      Serial.println("AUTO: Dat UOT -> TAT BOM");
    }
  } else {
    // ===== MANUAL =====
    if (manualPump) {
      digitalWrite(RELAY_PIN, LOW);
    } else {
      digitalWrite(RELAY_PIN, HIGH);
    }
  }

  // ===== MQTT GỬI DATA =====
  char tempStr[10];
  char soilStr[10];
  char humStr[10];

  dtostrf(temp, 1, 2, tempStr);
  dtostrf(hum, 1, 2, humStr);
  itoa(soilValue, soilStr, 10);

  client.publish("vuon/nhietdo", tempStr);
  client.publish("vuon/doamdat", soilStr);
  client.publish("vuon/doamkk", humStr);

  delay(3000);
}
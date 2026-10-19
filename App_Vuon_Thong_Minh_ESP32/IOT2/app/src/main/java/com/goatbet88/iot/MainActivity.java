package com.goatbet88.iot;

import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONObject;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SmartGarden";

    // ─── MQTT Config ───────────────────────────────────────────────
    private static final String BROKER_URL      = "tcp://broker.hivemq.com:1883";
    private static final String CLIENT_ID       = "SmartGarden_" + System.currentTimeMillis();
    private static final String TOPIC_PUMP      = "garden/esp32/pump";
    private static final String TOPIC_FAN       = "garden/esp32/fan";
    private static final String TOPIC_SERVO     = "garden/esp32/servo";
    private static final String TOPIC_SENSOR    = "garden/esp32/sensor";
    private static final String TOPIC_STATUS    = "garden/esp32/status";
    private static final String TOPIC_AUTO_MODE = "garden/esp32/automode";
    private static final String TOPIC_SCHEDULE  = "garden/esp32/schedule";
    private static final String TOPIC_ALERT     = "garden/esp32/alert";

    // ─── Camera ────────────────────────────────────────────────────
    private static final String ESP32_CAM_URL = "http://192.168.1.100";
    private static final String CAPTURE_URL   = ESP32_CAM_URL + "/capture";

    // ─── UI: Connection ────────────────────────────────────────────
    private Button   btnConnect, btnDisconnect;
    private TextView tvConnectionStatus;

    // ─── UI: Camera ────────────────────────────────────────────────
    private ImageView ivCamera;
    private Button    btnStartStream, btnStopStream, btnCapture;
    private TextView  tvCameraStatus;

    // ─── UI: Controls ──────────────────────────────────────────────
    private Switch   switchPump, switchFan, switchAutoMode;
    private SeekBar  seekBarServo;
    private TextView tvServoAngle;

    // ─── UI: Sensors ───────────────────────────────────────────────
    private TextView tvTemperature, tvHumidity, tvSoilMoisture, tvLightLevel;
    private TextView tvWaterLevel, tvLastUpdate;
    private CardView cardSensorWarning;
    private TextView tvWarningMessage;

    // ─── UI: Status ────────────────────────────────────────────────
    private TextView tvPumpStatus, tvFanStatus, tvServoStatus, tvAutoModeStatus;

    // ─── UI: Schedule ──────────────────────────────────────────────
    private Button   btnSetSchedule;
    private TextView tvScheduleInfo;

    // ─── UI: AI Chat ───────────────────────────────────────────────
    private CardView     cardAIChat;
    private LinearLayout btnOpenAIChat;
    private LinearLayout chatMessagesContainer;
    private ScrollView   chatScrollView;
    private EditText     etAIQuestion;
    private ImageButton  btnAskAI;
    private Button       btnQuick1, btnQuick2, btnQuick3;
    private TextView     tvAIStatus;
    private boolean      isChatOpen = false;

    // ─── MQTT ──────────────────────────────────────────────────────
    private MqttAndroidClient mqttClient;
    private boolean isConnected = false;

    // ─── Camera streaming ──────────────────────────────────────────
    private Handler  cameraHandler;
    private Runnable cameraRunnable;
    private boolean  isStreaming = false;

    // ─── Sensor data ───────────────────────────────────────────────
    private float currentTemperature  = 0;
    private float currentHumidity     = 0;
    private int   currentSoilMoisture = 0;
    private int   currentLightLevel   = 0;
    private int   currentWaterLevel   = 0;
    private int   currentServoAngle   = 0;

    // ─── AI ────────────────────────────────────────────────────────
    private AiChatHelper aiChatHelper;

    // ─── Schedule ──────────────────────────────────────────────────
    private WateringScheduleManager scheduleManager;
    private ScheduleDialogHelper    scheduleDialogHelper;

    // ─── Notifications ─────────────────────────────────────────────
    private GardenNotificationHelper notifHelper;
    private long lastSoilNotifTime  = 0;
    private long lastWaterNotifTime = 0;
    private long lastTempNotifTime  = 0;
    private static final long NOTIF_COOLDOWN_MS = 30 * 60 * 1000L;

    // ─── Auto reconnect ────────────────────────────────────────────
    private static final int  RECONNECT_DELAY_MS  = 5_000;
    private static final int  MAX_RECONNECT_TRIES = 10;
    private int     reconnectCount  = 0;
    private boolean isReconnecting  = false;
    private final Handler  reconnectHandler  = new Handler(Looper.getMainLooper());
    private final Runnable reconnectRunnable = this::attemptReconnect;

    // ═══════════════════════════════════════════════════════════════
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initAIChat();
        initMqttClient();
        setupListeners();
        cameraHandler = new Handler(Looper.getMainLooper());

        // Lịch tưới
        scheduleManager      = new WateringScheduleManager(this);
        scheduleDialogHelper = new ScheduleDialogHelper(this, scheduleManager);
        scheduleDialogHelper.setOnChangedListener(this::updateScheduleUI);

        WateringScheduleManager.setTriggerListener(new WateringScheduleManager.OnTriggerListener() {
            @Override
            public void onWaterStart(int durationSeconds) {
                publishMessage(TOPIC_PUMP, "ON");
                switchPump.setChecked(true);
                tvPumpStatus.setText("Đang tưới (lịch tự động)");
                if (notifHelper != null)
                    notifHelper.notifyScheduleStart(
                            new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()),
                            durationSeconds / 60);
                Toast.makeText(MainActivity.this,
                        "⏰ Lịch tưới: bật bơm " + durationSeconds / 60 + " phút",
                        Toast.LENGTH_LONG).show();
            }
            @Override
            public void onWaterStop() {
                publishMessage(TOPIC_PUMP, "OFF");
                switchPump.setChecked(false);
                tvPumpStatus.setText("Đang tắt");
                if (notifHelper != null) notifHelper.notifyScheduleDone(0);
                Toast.makeText(MainActivity.this, "⏰ Lịch tưới hoàn thành",
                        Toast.LENGTH_SHORT).show();
            }
        });

        handleScheduleIntent(getIntent());
        updateScheduleUI();

        // Notification
        notifHelper = new GardenNotificationHelper(this);
        requestNotificationPermission();
    }

    // ───────────────────────────────────────────────────────────────
    // initViews
    // ───────────────────────────────────────────────────────────────
    private void initViews() {
        btnConnect         = findViewById(R.id.btnConnect);
        btnDisconnect      = findViewById(R.id.btnDisconnect);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);

        ivCamera       = findViewById(R.id.ivCamera);
        btnStartStream = findViewById(R.id.btnStartStream);
        btnStopStream  = findViewById(R.id.btnStopStream);
        btnCapture     = findViewById(R.id.btnCapture);
        tvCameraStatus = findViewById(R.id.tvCameraStatus);

        switchPump     = findViewById(R.id.switchPump);
        switchFan      = findViewById(R.id.switchFan);
        switchAutoMode = findViewById(R.id.switchAutoMode);
        seekBarServo   = findViewById(R.id.seekBarServo);
        tvServoAngle   = findViewById(R.id.tvServoAngle);

        tvTemperature  = findViewById(R.id.tvTemperature);
        tvHumidity     = findViewById(R.id.tvHumidity);
        tvSoilMoisture = findViewById(R.id.tvSoilMoisture);
        tvLightLevel   = findViewById(R.id.tvLightLevel);
        tvWaterLevel   = findViewById(R.id.tvWaterLevel);
        tvLastUpdate   = findViewById(R.id.tvLastUpdate);

        cardSensorWarning = findViewById(R.id.cardSensorWarning);
        tvWarningMessage  = findViewById(R.id.tvWarningMessage);
        cardSensorWarning.setVisibility(View.GONE);

        tvPumpStatus     = findViewById(R.id.tvPumpStatus);
        tvFanStatus      = findViewById(R.id.tvFanStatus);
        tvServoStatus    = findViewById(R.id.tvServoStatus);
        tvAutoModeStatus = findViewById(R.id.tvAutoModeStatus);

        btnSetSchedule = findViewById(R.id.btnSetSchedule);
        tvScheduleInfo = findViewById(R.id.tvScheduleInfo);

        setControlsEnabled(false);
        btnDisconnect.setEnabled(false);
        btnStopStream.setEnabled(false);
    }

    // ───────────────────────────────────────────────────────────────
    // initAIChat
    // ───────────────────────────────────────────────────────────────
    private void initAIChat() {
        cardAIChat            = findViewById(R.id.cardAIChat);
        btnOpenAIChat         = findViewById(R.id.btnOpenAIChat);
        chatMessagesContainer = findViewById(R.id.chatMessagesContainer);
        chatScrollView        = findViewById(R.id.chatScrollView);
        etAIQuestion          = findViewById(R.id.etAIQuestion);
        btnAskAI              = findViewById(R.id.btnAskAI);
        btnQuick1             = findViewById(R.id.btnQuick1);
        btnQuick2             = findViewById(R.id.btnQuick2);
        btnQuick3             = findViewById(R.id.btnQuick3);
        tvAIStatus            = findViewById(R.id.tvAIStatus);

        cardAIChat.setVisibility(View.GONE);

        aiChatHelper = new AiChatHelper(this);

        aiChatHelper.setDeviceCommandCallback((device, action) -> {
            switch (device) {
                case "pump":
                    publishMessage(TOPIC_PUMP, action);
                    switchPump.setChecked(action.equals("ON"));
                    break;
                case "fan":
                    publishMessage(TOPIC_FAN, action);
                    switchFan.setChecked(action.equals("ON"));
                    break;
                case "servo":
                    publishMessage(TOPIC_SERVO, action);
                    try { seekBarServo.setProgress(Integer.parseInt(action)); }
                    catch (Exception ignored) {}
                    break;
                case "automode":
                    publishMessage(TOPIC_AUTO_MODE, action);
                    switchAutoMode.setChecked(action.equals("ON"));
                    setManualControlsEnabled(!action.equals("ON"));
                    break;
            }
        });

        aiChatHelper.initialize(new AiChatHelper.AiCallback() {
            @Override public void onReady() {
                tvAIStatus.setText("🟢 Sẵn sàng");
                addBotMessage("👋 Xin chào! Tôi là trợ lý AI cho vườn thông minh của bạn.\n\n"
                        + "Thử hỏi tôi:\n"
                        + "• \"Phân tích tình trạng vườn\"\n"
                        + "• \"Có cần tưới không?\"\n"
                        + "• \"Bật bơm\" / \"Tắt quạt\"\n"
                        + "• \"Gợi ý lịch tưới hôm nay\"");
            }
            @Override public void onResponse(String r) {}
            @Override public void onError(String e) { tvAIStatus.setText("🔴 Dự phòng"); }
        });

        // Mở/đóng chat → scroll đến ngay
        btnOpenAIChat.setOnClickListener(v -> {
            isChatOpen = !isChatOpen;
            cardAIChat.setVisibility(isChatOpen ? View.VISIBLE : View.GONE);
            TextView tvLabel = (TextView) ((LinearLayout) btnOpenAIChat).getChildAt(1);
            if (tvLabel != null) tvLabel.setText(isChatOpen ? " Đóng" : " AI");
            if (isChatOpen) {
                cardAIChat.post(() -> {
                    ScrollView outerScroll = findViewById(R.id.scrollView);
                    if (outerScroll != null) outerScroll.smoothScrollTo(0, cardAIChat.getTop());
                    scrollChatToBottom();
                });
            }
        });

        btnAskAI.setOnClickListener(v ->
                sendUserMessage(etAIQuestion.getText().toString().trim()));

        // Fix tiếng Việt: dùng textMultiLine + maxLines=1
        etAIQuestion.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                    || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                sendUserMessage(etAIQuestion.getText().toString().trim());
                return true;
            }
            return false;
        });

        etAIQuestion.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                String text = etAIQuestion.getText().toString().trim();
                if (!text.isEmpty()) { sendUserMessage(text); return true; }
            }
            return false;
        });

        btnQuick1.setOnClickListener(v -> sendUserMessage("Phân tích tình trạng vườn"));
        btnQuick2.setOnClickListener(v -> sendUserMessage("Có cần tưới nước không?"));
        btnQuick3.setOnClickListener(v -> sendUserMessage("Gợi ý lịch tưới"));
    }

    // ───────────────────────────────────────────────────────────────
    // MQTT
    // ───────────────────────────────────────────────────────────────
    private void initMqttClient() {
        mqttClient = new MqttAndroidClient(getApplicationContext(), BROKER_URL, CLIENT_ID);
        mqttClient.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                runOnUiThread(() -> {
                    isConnected = false;
                    tvConnectionStatus.setText("🔴 Mất kết nối");
                    setControlsEnabled(false);
                    Log.w(TAG, "connectionLost: " + (cause != null ? cause.getMessage() : "unknown"));
                    if (notifHelper != null) notifHelper.notifyMqttLost();
                    startAutoReconnect();
                });
            }
            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                handleIncomingMessage(topic, new String(message.getPayload()));
            }
            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {}
        });
    }

    private void setupListeners() {
        btnConnect.setOnClickListener(v    -> connectToMqtt());
        btnDisconnect.setOnClickListener(v -> disconnectFromMqtt());

        btnStartStream.setOnClickListener(v -> startCameraStream());
        btnStopStream.setOnClickListener(v  -> stopCameraStream());
        btnCapture.setOnClickListener(v     -> captureImage());

        switchPump.setOnCheckedChangeListener((b, isChecked) -> {
            if (b.isPressed()) publishMessage(TOPIC_PUMP, isChecked ? "ON" : "OFF");
        });
        switchFan.setOnCheckedChangeListener((b, isChecked) -> {
            if (b.isPressed()) publishMessage(TOPIC_FAN, isChecked ? "ON" : "OFF");
        });
        switchAutoMode.setOnCheckedChangeListener((b, isChecked) -> {
            if (b.isPressed()) {
                publishMessage(TOPIC_AUTO_MODE, isChecked ? "ON" : "OFF");
                setManualControlsEnabled(!isChecked);
            }
        });

        seekBarServo.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tvServoAngle.setText(p + "°");
                currentServoAngle = p;
                if (p == 0)        tvServoStatus.setText("Đóng hoàn toàn");
                else if (p >= 170) tvServoStatus.setText("Mở hoàn toàn");
                else               tvServoStatus.setText("Mở " + p + "°");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                if (isConnected && !switchAutoMode.isChecked())
                    publishMessage(TOPIC_SERVO, String.valueOf(currentServoAngle));
            }
        });

        btnSetSchedule.setOnClickListener(v -> showScheduleDialog());
    }

    // ─── Connect ──────────────────────────────────────────────────
    private void connectToMqtt() {
        try {
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(false); // tự quản lý
            opts.setCleanSession(false);
            opts.setConnectionTimeout(10);
            opts.setKeepAliveInterval(20);

            tvConnectionStatus.setText("🟡 Đang kết nối...");

            mqttClient.connect(opts, null, new IMqttActionListener() {
                @Override public void onSuccess(IMqttToken t) {
                    runOnUiThread(() -> {
                        isConnected    = true;
                        isReconnecting = false;
                        reconnectCount = 0;
                        reconnectHandler.removeCallbacks(reconnectRunnable);
                        tvConnectionStatus.setText("🟢 Đã kết nối");
                        Toast.makeText(MainActivity.this, "✅ Kết nối thành công!", Toast.LENGTH_SHORT).show();
                        subscribeToTopics();
                        setControlsEnabled(true);
                        btnConnect.setEnabled(false);
                        btnDisconnect.setEnabled(true);
                    });
                }
                @Override public void onFailure(IMqttToken t, Throwable e) {
                    runOnUiThread(() -> {
                        isConnected = false;
                        tvConnectionStatus.setText("🔴 Kết nối thất bại");
                        Toast.makeText(MainActivity.this, "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        } catch (MqttException e) {
            Toast.makeText(this, "Lỗi: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void disconnectFromMqtt() {
        try {
            stopCameraStream();
            reconnectHandler.removeCallbacks(reconnectRunnable);
            isReconnecting = false;
            mqttClient.disconnect(null, new IMqttActionListener() {
                @Override public void onSuccess(IMqttToken t) {
                    runOnUiThread(() -> {
                        isConnected = false;
                        tvConnectionStatus.setText("⚪ Đã ngắt kết nối");
                        setControlsEnabled(false);
                        btnConnect.setEnabled(true);
                        btnDisconnect.setEnabled(false);
                    });
                }
                @Override public void onFailure(IMqttToken t, Throwable e) {}
            });
        } catch (MqttException e) { Log.e(TAG, "Disconnect error", e); }
    }

    private void subscribeToTopics() {
        try {
            mqttClient.subscribe(TOPIC_SENSOR,    0);
            mqttClient.subscribe(TOPIC_STATUS,    0);
            mqttClient.subscribe(TOPIC_ALERT,     0);
            mqttClient.subscribe(TOPIC_AUTO_MODE, 0);
            mqttClient.subscribe(TOPIC_SCHEDULE,  0);
        } catch (MqttException e) { Log.e(TAG, "Subscribe error", e); }
    }

    private void publishMessage(String topic, String message) {
        if (!isConnected) {
            Toast.makeText(this, "⚠️ Chưa kết nối MQTT!", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            MqttMessage msg = new MqttMessage(message.getBytes());
            msg.setQos(0);
            mqttClient.publish(topic, msg);
        } catch (MqttException e) {
            Toast.makeText(this, "Lỗi gửi lệnh!", Toast.LENGTH_SHORT).show();
        }
    }

    // ─── Auto Reconnect ───────────────────────────────────────────
    private void startAutoReconnect() {
        if (isReconnecting) return;
        isReconnecting = true;
        reconnectCount = 0;
        scheduleNextReconnect();
    }

    private void scheduleNextReconnect() {
        if (reconnectCount >= MAX_RECONNECT_TRIES) {
            isReconnecting = false;
            runOnUiThread(() -> {
                tvConnectionStatus.setText("🔴 Mất kết nối (thử lại thủ công)");
                Toast.makeText(this, "❌ Không thể kết nối lại sau " + MAX_RECONNECT_TRIES + " lần",
                        Toast.LENGTH_LONG).show();
                btnConnect.setEnabled(true);
            });
            return;
        }
        long delay = Math.min(RECONNECT_DELAY_MS * (1L << reconnectCount), 60_000);
        runOnUiThread(() -> tvConnectionStatus.setText(
                "🟡 Kết nối lại... (" + (reconnectCount + 1) + "/" + MAX_RECONNECT_TRIES + ")"));
        reconnectHandler.postDelayed(reconnectRunnable, delay);
    }

    private void attemptReconnect() {
        if (isConnected) { isReconnecting = false; return; }
        reconnectCount++;
        try {
            if (mqttClient != null && !mqttClient.isConnected()) {
                MqttConnectOptions opts = new MqttConnectOptions();
                opts.setAutomaticReconnect(false);
                opts.setCleanSession(false);
                opts.setConnectionTimeout(8);
                opts.setKeepAliveInterval(20);
                mqttClient.connect(opts, null, new IMqttActionListener() {
                    @Override public void onSuccess(IMqttToken t) {
                        runOnUiThread(() -> {
                            isConnected    = true;
                            isReconnecting = false;
                            reconnectCount = 0;
                            reconnectHandler.removeCallbacks(reconnectRunnable);
                            tvConnectionStatus.setText("🟢 Đã kết nối lại");
                            setControlsEnabled(true);
                            btnConnect.setEnabled(false);
                            btnDisconnect.setEnabled(true);
                            subscribeToTopics();
                            if (notifHelper != null) notifHelper.cancelMqttLost();
                            Toast.makeText(MainActivity.this, "✅ Đã kết nối lại MQTT",
                                    Toast.LENGTH_SHORT).show();
                        });
                    }
                    @Override public void onFailure(IMqttToken t, Throwable e) {
                        Log.w(TAG, "Reconnect #" + reconnectCount + " failed");
                        runOnUiThread(MainActivity.this::scheduleNextReconnect);
                    }
                });
            }
        } catch (MqttException e) {
            Log.e(TAG, "Reconnect exception", e);
            scheduleNextReconnect();
        }
    }

    // ─── Incoming message ─────────────────────────────────────────
    private void handleIncomingMessage(String topic, String payload) {
        runOnUiThread(() -> {
            try {
                JSONObject json = new JSONObject(payload);

                if (topic.equals(TOPIC_SENSOR)) {
                    if (json.has("temperature")) {
                        currentTemperature = (float) json.getDouble("temperature");
                        tvTemperature.setText(String.format("%.1f°C", currentTemperature));
                    }
                    if (json.has("humidity")) {
                        currentHumidity = (float) json.getDouble("humidity");
                        tvHumidity.setText(String.format("%.1f%%", currentHumidity));
                    }
                    if (json.has("soilMoisture")) {
                        currentSoilMoisture = json.getInt("soilMoisture");
                        tvSoilMoisture.setText(currentSoilMoisture + "%");
                        updateSoilMoistureColor(currentSoilMoisture);
                    }
                    if (json.has("lightLevel")) {
                        currentLightLevel = json.getInt("lightLevel");
                        tvLightLevel.setText(currentLightLevel + "%");
                    }
                    if (json.has("waterLevel")) {
                        currentWaterLevel = json.getInt("waterLevel");
                        tvWaterLevel.setText(currentWaterLevel + "%");
                        updateWaterLevelColor(currentWaterLevel);
                    }
                    tvLastUpdate.setText(
                            new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
                    checkSensorWarnings();

                    if (aiChatHelper != null) {
                        aiChatHelper.updateSensorData(
                                currentTemperature, currentHumidity,
                                currentSoilMoisture, currentWaterLevel,
                                currentLightLevel,
                                switchPump.isChecked(), switchFan.isChecked(),
                                switchAutoMode.isChecked());
                    }

                } else if (topic.equals(TOPIC_STATUS)) {
                    if (json.has("pump")) {
                        boolean on = json.getString("pump").equals("ON");
                        tvPumpStatus.setText(on ? "Đang chạy" : "Đang tắt");
                        tvPumpStatus.setTextColor(on ? 0xFF1E88E5 : 0xFF9E9E9E);
                        switchPump.setChecked(on);
                    }
                    if (json.has("fan")) {
                        boolean on = json.getString("fan").equals("ON");
                        tvFanStatus.setText(on ? "Đang chạy" : "Đang tắt");
                        tvFanStatus.setTextColor(on ? 0xFF7B1FA2 : 0xFF9E9E9E);
                        switchFan.setChecked(on);
                    }
                    if (json.has("servo")) {
                        int angle = json.getInt("servo");
                        tvServoStatus.setText(angle == 0 ? "Đóng hoàn toàn"
                                : angle >= 170 ? "Mở hoàn toàn" : "Mở " + angle + "°");
                        seekBarServo.setProgress(angle);
                    }
                    if (json.has("autoMode")) {
                        boolean auto = json.getBoolean("autoMode");
                        tvAutoModeStatus.setText(auto ? "Tự động" : "Thủ công");
                        switchAutoMode.setChecked(auto);
                        setManualControlsEnabled(!auto);
                    }

                } else if (topic.equals(TOPIC_ALERT)) {
                    Toast.makeText(this, "🔔 " + json.getString("message"), Toast.LENGTH_LONG).show();
                }

            } catch (Exception e) {
                Log.e(TAG, "Parse error", e);
            }
        });
    }

    // ─── AI Chat ──────────────────────────────────────────────────
    private void sendUserMessage(String text) {
        if (text == null || text.isEmpty()) return;
        addUserMessage(text);
        etAIQuestion.setText("");

        aiChatHelper.updateSensorData(
                currentTemperature, currentHumidity,
                currentSoilMoisture, currentWaterLevel,
                currentLightLevel,
                switchPump.isChecked(), switchFan.isChecked(),
                switchAutoMode.isChecked());

        final LinearLayout[] typingView = {addTypingIndicator()};
        aiChatHelper.ask(text, new AiChatHelper.AiCallback() {
            @Override public void onResponse(String response) {
                runOnUiThread(() -> {
                    chatMessagesContainer.removeView(typingView[0]);
                    addBotMessage(response);
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    chatMessagesContainer.removeView(typingView[0]);
                    addBotMessage("❌ " + error);
                });
            }
            @Override public void onReady() {}
        });
    }

    private void addUserMessage(String text) {
        runOnUiThread(() -> {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.END);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.setMargins(dpToPx(56), dpToPx(4), dpToPx(12), dpToPx(4));
            row.setLayoutParams(rp);

            TextView bubble = new TextView(this);
            bubble.setText(text);
            bubble.setTextColor(0xFFFFFFFF);
            bubble.setTextSize(14f);
            bubble.setLineSpacing(dpToPx(2), 1f);
            bubble.setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10));
            bubble.setBackground(buildBubble(0xFF2E7D32, true));
            row.addView(bubble);

            chatMessagesContainer.addView(row);
            scrollChatToBottom();
        });
    }

    private void addBotMessage(String text) {
        runOnUiThread(() -> {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.START);
            row.setLayoutParams(rowParams(dpToPx(12), dpToPx(4), dpToPx(56), dpToPx(4)));

            TextView av = new TextView(this);
            av.setText("🤖");
            av.setTextSize(20f);
            av.setPadding(0, dpToPx(4), dpToPx(8), 0);
            av.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(av);

            TextView bubble = new TextView(this);
            bubble.setText(text);
            bubble.setTextColor(0xFF1B5E20);
            bubble.setTextSize(14f);
            bubble.setLineSpacing(dpToPx(2), 1f);
            bubble.setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10));
            bubble.setBackground(buildBubble(0xFFFFFFFF, false));
            bubble.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(bubble);

            chatMessagesContainer.addView(row);
            scrollChatToBottom();
        });
    }

    private LinearLayout addTypingIndicator() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);
        row.setLayoutParams(rowParams(dpToPx(12), dpToPx(4), dpToPx(56), dpToPx(4)));

        TextView av = new TextView(this);
        av.setText("🤖");
        av.setTextSize(20f);
        av.setPadding(0, dpToPx(4), dpToPx(8), 0);
        row.addView(av);

        TextView typing = new TextView(this);
        typing.setText("  ●  ●  ●  ");
        typing.setTextColor(0xFF81C784);
        typing.setTextSize(16f);
        typing.setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF1F8E9);
        bg.setCornerRadii(new float[]{dpToPx(4), dpToPx(4), dpToPx(18), dpToPx(18),
                dpToPx(18), dpToPx(18), dpToPx(18), dpToPx(18)});
        typing.setBackground(bg);
        row.addView(typing);

        chatMessagesContainer.addView(row);
        scrollChatToBottom();
        return row;
    }

    private GradientDrawable buildBubble(int color, boolean isUser) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        float r = dpToPx(18), s = dpToPx(4);
        d.setCornerRadii(isUser
                ? new float[]{r, r, r, r, s, s, r, r}
                : new float[]{s, s, r, r, r, r, r, r});
        if (!isUser) d.setStroke(dpToPx(1), 0xFFE8F5E9);
        return d;
    }

    private LinearLayout.LayoutParams rowParams(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    private void scrollChatToBottom() {
        chatScrollView.post(() -> chatScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // ─── Sensor warnings ──────────────────────────────────────────
    private void checkSensorWarnings() {
        StringBuilder w = new StringBuilder();
        long now = System.currentTimeMillis();

        if (currentSoilMoisture < 30) {
            w.append("🌱 Đất quá khô! Cần tưới nước.\n");
            if (notifHelper != null && now - lastSoilNotifTime > NOTIF_COOLDOWN_MS) {
                notifHelper.notifySoilDry(currentSoilMoisture);
                lastSoilNotifTime = now;
            }
        }
        if (currentWaterLevel < 20) {
            w.append("🚰 Bình nước sắp hết!\n");
            if (notifHelper != null && now - lastWaterNotifTime > NOTIF_COOLDOWN_MS) {
                notifHelper.notifyWaterLow(currentWaterLevel);
                lastWaterNotifTime = now;
            }
        }
        if (currentTemperature > 35) {
            w.append("🌡️ Nhiệt độ quá cao!\n");
            if (notifHelper != null && now - lastTempNotifTime > NOTIF_COOLDOWN_MS) {
                notifHelper.notifyTempHigh(currentTemperature);
                lastTempNotifTime = now;
            }
        }

        if (w.length() > 0) {
            cardSensorWarning.setVisibility(View.VISIBLE);
            tvWarningMessage.setText(w.toString().trim());
        } else {
            cardSensorWarning.setVisibility(View.GONE);
        }
    }

    private void updateSoilMoistureColor(int m) {
        tvSoilMoisture.setTextColor(m < 30 ? 0xFFE53935 : m < 60 ? 0xFFF57C00 : 0xFF43A047);
    }

    private void updateWaterLevelColor(int l) {
        tvWaterLevel.setTextColor(l < 20 ? 0xFFE53935 : l < 50 ? 0xFFF57C00 : 0xFF039BE5);
    }

    // ─── Schedule ─────────────────────────────────────────────────
    private void showScheduleDialog() {
        scheduleDialogHelper.showManageDialog();
    }

    private void updateScheduleUI() {
        List<WateringScheduleManager.WateringSchedule> list = scheduleManager.getSchedules();
        if (list.isEmpty()) {
            tvScheduleInfo.setText("Chưa cài lịch tưới");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (WateringScheduleManager.WateringSchedule s : list) {
            sb.append(s.enabled ? "🟢 " : "⏸ ")
                    .append(s.getTimeDisplay()).append("  ")
                    .append(s.getDurationDisplay()).append("  [")
                    .append(s.getDaysDisplay()).append("]\n");
        }
        tvScheduleInfo.setText(sb.toString().trim());
    }

    private void handleScheduleIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra("auto_pump_on", false)) {
            int dur = intent.getIntExtra("auto_duration", 60);
            reconnectHandler.postDelayed(() -> {
                if (isConnected) {
                    publishMessage(TOPIC_PUMP, "ON");
                    Toast.makeText(this, "⏰ Lịch tưới tự động kích hoạt", Toast.LENGTH_LONG).show();
                }
            }, 3000);
        } else if (intent.getBooleanExtra("auto_pump_off", false)) {
            reconnectHandler.postDelayed(() -> {
                if (isConnected) publishMessage(TOPIC_PUMP, "OFF");
            }, 3000);
        }
    }

    // ─── Notification permission ──────────────────────────────────
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }

    // ─── Camera ───────────────────────────────────────────────────
    private void startCameraStream() {
        isStreaming = true;
        btnStartStream.setEnabled(false);
        btnStopStream.setEnabled(true);
        tvCameraStatus.setText("● Đang phát trực tiếp...");
        cameraRunnable = new Runnable() {
            @Override public void run() {
                if (isStreaming) { loadCameraImage(); cameraHandler.postDelayed(this, 100); }
            }
        };
        cameraHandler.post(cameraRunnable);
    }

    private void stopCameraStream() {
        isStreaming = false;
        if (cameraRunnable != null) cameraHandler.removeCallbacks(cameraRunnable);
        btnStartStream.setEnabled(true);
        btnStopStream.setEnabled(false);
        tvCameraStatus.setText("● Đã dừng stream");
    }

    private void loadCameraImage() {
        new Thread(() -> {
            try {
                URL url = new URL(CAPTURE_URL);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setDoInput(true); c.connect();
                Bitmap bmp = BitmapFactory.decodeStream(c.getInputStream());
                runOnUiThread(() -> ivCamera.setImageBitmap(bmp));
            } catch (Exception e) {
                runOnUiThread(() -> { if (isStreaming) tvCameraStatus.setText("● Lỗi kết nối camera"); });
            }
        }).start();
    }

    private void captureImage() {
        new Thread(() -> {
            try {
                URL url = new URL(CAPTURE_URL);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setDoInput(true); c.connect();
                Bitmap bmp = BitmapFactory.decodeStream(c.getInputStream());
                runOnUiThread(() -> {
                    ivCamera.setImageBitmap(bmp);
                    Toast.makeText(this, "📸 Đã chụp ảnh!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Lỗi chụp ảnh!", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ─── Misc ─────────────────────────────────────────────────────
    private void setControlsEnabled(boolean enabled) {
        switchPump.setEnabled(enabled);
        switchFan.setEnabled(enabled);
        switchAutoMode.setEnabled(enabled);
        seekBarServo.setEnabled(enabled);
        btnSetSchedule.setEnabled(enabled);
        btnStartStream.setEnabled(enabled);
        btnCapture.setEnabled(enabled);
    }

    private void setManualControlsEnabled(boolean enabled) {
        switchPump.setEnabled(enabled);
        switchFan.setEnabled(enabled);
        seekBarServo.setEnabled(enabled);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleScheduleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCameraStream();
        reconnectHandler.removeCallbacks(reconnectRunnable);
        WateringScheduleManager.setTriggerListener(null);
        if (mqttClient != null && isConnected) {
            try { mqttClient.disconnect(); } catch (MqttException e) { Log.e(TAG, "onDestroy", e); }
        }
    }
}
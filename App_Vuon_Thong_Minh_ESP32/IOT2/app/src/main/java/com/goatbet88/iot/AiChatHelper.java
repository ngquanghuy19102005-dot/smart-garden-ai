package com.goatbet88.iot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * AiChatHelper - Chatbox AI miễn phí cho Smart Garden IoT
 *
 * Cách hoạt động:
 *   1. Ưu tiên dùng Gemini 1.5 Flash (free tier: 15 req/phút, KHÔNG cần thẻ tín dụng)
 *      → Lấy API key miễn phí tại: https://aistudio.google.com/app/apikey
 *   2. Fallback tự động sang Rule-based AI khi mất mạng hoặc hết quota
 *
 * Tính năng chatbox:
 *   - Chat tự do bằng văn bản tiếng Việt
 *   - Nhận diện lệnh điều khiển thiết bị từ câu chat ("bật bơm", "tắt quạt"...)
 *   - Phân tích tình trạng vườn theo dữ liệu cảm biến thực
 *   - Gợi ý lịch tưới, cảnh báo ngưỡng
 *   - Hỗ trợ lịch sử hội thoại (context-aware)
 */
public class AiChatHelper {

    private static final String TAG = "AiChatHelper";

    // =====================================================================
    // ĐỔI API KEY CỦA BẠN VÀO ĐÂY (miễn phí tại aistudio.google.com)
    // =====================================================================
    private static final String GEMINI_API_KEY = "AIzaSyCheOba3OFaVtj1xLaKE--l3ts1HVh7-og";
    // =====================================================================

    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                    "gemini-1.5-flash:generateContent?key=" + GEMINI_API_KEY;

    // Giới hạn lịch sử hội thoại (tránh vượt token)
    private static final int MAX_HISTORY = 10;

    private final Context context;
    private final Handler mainHandler;

    // Lịch sử hội thoại để AI hiểu ngữ cảnh
    private final List<ChatMessage> chatHistory = new ArrayList<>();

    // Dữ liệu cảm biến hiện tại (được cập nhật từ MainActivity)
    private float temperature = 0;
    private float humidity = 0;
    private int soilMoisture = 0;
    private int waterLevel = 0;
    private int lightLevel = 0;
    private boolean pumpOn = false;
    private boolean fanOn = false;
    private boolean autoMode = false;

    // -------------------------------------------------------------------------
    // Interface callbacks
    // -------------------------------------------------------------------------

    public interface AiCallback {
        void onResponse(String response);
        void onError(String error);
        void onReady();
    }

    /** Callback đặc biệt khi AI nhận diện lệnh điều khiển thiết bị */
    public interface DeviceCommandCallback {
        /**
         * @param device  "pump" | "fan" | "servo" | "automode"
         * @param action  "ON" | "OFF" | số góc servo (VD: "90")
         */
        void onDeviceCommand(String device, String action);
    }

    private DeviceCommandCallback deviceCommandCallback;

    // -------------------------------------------------------------------------
    // Model nội bộ cho lịch sử chat
    // -------------------------------------------------------------------------

    public static class ChatMessage {
        public final String role;    // "user" hoặc "model"
        public final String text;
        public final long timestamp;

        public ChatMessage(String role, String text) {
            this.role = role;
            this.text = text;
            this.timestamp = System.currentTimeMillis();
        }
    }

    // -------------------------------------------------------------------------
    // Khởi tạo
    // -------------------------------------------------------------------------

    public AiChatHelper(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /** Đăng ký callback để nhận lệnh điều khiển thiết bị từ chat */
    public void setDeviceCommandCallback(DeviceCommandCallback callback) {
        this.deviceCommandCallback = callback;
    }

    /** Gọi 1 lần sau khi khởi tạo */
    public void initialize(AiCallback callback) {
        // Kiểm tra API key đã điền chưa
        if (GEMINI_API_KEY.equals("YOUR_GEMINI_API_KEY_HERE")) {
            Log.w(TAG, "Chưa điền API key, dùng rule-based AI");
        }
        // Luôn báo ready (rule-based luôn hoạt động)
        mainHandler.post(callback::onReady);
    }

    // -------------------------------------------------------------------------
    // Cập nhật dữ liệu cảm biến (gọi từ MainActivity khi nhận MQTT)
    // -------------------------------------------------------------------------

    public void updateSensorData(float temperature, float humidity,
                                 int soilMoisture, int waterLevel,
                                 int lightLevel, boolean pumpOn,
                                 boolean fanOn, boolean autoMode) {
        this.temperature  = temperature;
        this.humidity     = humidity;
        this.soilMoisture = soilMoisture;
        this.waterLevel   = waterLevel;
        this.lightLevel   = lightLevel;
        this.pumpOn       = pumpOn;
        this.fanOn        = fanOn;
        this.autoMode     = autoMode;
    }

    // -------------------------------------------------------------------------
    // Hàm hỏi AI chính - gọi từ bên ngoài
    // -------------------------------------------------------------------------

    /**
     * Gửi câu hỏi/lệnh đến AI.
     * AI tự chọn Gemini hoặc rule-based tuỳ tình trạng mạng/key.
     */
    public void ask(String userMessage, AiCallback callback) {
        if (userMessage == null || userMessage.trim().isEmpty()) return;

        // 1. Lưu vào lịch sử
        chatHistory.add(new ChatMessage("user", userMessage));
        if (chatHistory.size() > MAX_HISTORY * 2) {
            chatHistory.subList(0, 2).clear(); // xoá cặp user/model cũ nhất
        }

        // 2. Kiểm tra lệnh điều khiển thiết bị trước (phản hồi nhanh)
        String deviceCommand = detectDeviceCommand(userMessage);
        if (deviceCommand != null) {
            executeDeviceCommand(deviceCommand, userMessage, callback);
            return;
        }

        // 3. Gửi lên Gemini hoặc fallback rule-based
        if (!GEMINI_API_KEY.equals("YOUR_GEMINI_API_KEY_HERE")) {
            askGemini(userMessage, callback);
        } else {
            askRuleBased(userMessage, callback);
        }
    }

    // =========================================================================
    // GEMINI 1.5 FLASH - Free tier
    // =========================================================================

    private void askGemini(String userMessage, AiCallback callback) {
        new Thread(() -> {
            try {
                // Xây dựng system prompt với dữ liệu cảm biến
                String systemPrompt = buildSystemPrompt();

                // Xây dựng body JSON với lịch sử hội thoại
                JSONObject body = new JSONObject();

                // System instruction
                JSONObject systemInstruction = new JSONObject();
                JSONArray sysParts = new JSONArray();
                JSONObject sysPart = new JSONObject();
                sysPart.put("text", systemPrompt);
                sysParts.put(sysPart);
                systemInstruction.put("parts", sysParts);
                body.put("systemInstruction", systemInstruction);

                // Contents (lịch sử + tin nhắn hiện tại)
                JSONArray contents = new JSONArray();
                for (ChatMessage msg : chatHistory) {
                    JSONObject content = new JSONObject();
                    content.put("role", msg.role);
                    JSONArray parts = new JSONArray();
                    JSONObject part = new JSONObject();
                    part.put("text", msg.text);
                    parts.put(part);
                    content.put("parts", parts);
                    contents.put(content);
                }
                body.put("contents", contents);

                // Generation config
                JSONObject genConfig = new JSONObject();
                genConfig.put("temperature", 0.7);
                genConfig.put("maxOutputTokens", 500);
                body.put("generationConfig", genConfig);

                // Gọi API
                URL url = new URL(GEMINI_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = body.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    StringBuilder response = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            response.append(line);
                        }
                    }

                    // Parse response
                    JSONObject jsonResponse = new JSONObject(response.toString());
                    String aiText = jsonResponse
                            .getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text");

                    // Lưu response vào lịch sử
                    chatHistory.add(new ChatMessage("model", aiText));

                    mainHandler.post(() -> callback.onResponse(aiText));

                } else {
                    // Lỗi API → fallback
                    Log.w(TAG, "Gemini API error: " + responseCode + " → fallback rule-based");
                    mainHandler.post(() -> askRuleBased(userMessage, callback));
                }

            } catch (Exception e) {
                Log.e(TAG, "Gemini exception → fallback", e);
                // Mất mạng hoặc lỗi → fallback rule-based
                mainHandler.post(() -> askRuleBased(userMessage, callback));
            }
        }).start();
    }

    // =========================================================================
    // RULE-BASED AI - Fallback, không cần internet
    // =========================================================================

    public void askRuleBased(String question, AiCallback callback) {
        new Thread(() -> {
            String response = generateRuleBasedResponse(question);
            chatHistory.add(new ChatMessage("model", response));
            mainHandler.post(() -> callback.onResponse(response));
        }).start();
    }

    private String generateRuleBasedResponse(String question) {
        StringBuilder sb = new StringBuilder();
        String q = question.toLowerCase().trim();

        if (q.contains("tưới") || q.contains("nước") || q.contains("đất")) {
            if (soilMoisture < 20) {
                sb.append("🚨 Đất rất khô (").append(soilMoisture).append("%)! Cần tưới NGAY.\n");
                sb.append("→ Bật bơm ít nhất 10-15 phút.");
            } else if (soilMoisture < 40) {
                sb.append("⚠️ Đất hơi khô (").append(soilMoisture).append("%).\n");
                sb.append("→ Nên tưới trong vòng 2 giờ tới.");
            } else if (soilMoisture < 70) {
                sb.append("✅ Độ ẩm đất tốt (").append(soilMoisture).append("%).\n");
                sb.append("→ Chưa cần tưới, kiểm tra lại sau 4-6 giờ.");
            } else {
                sb.append("💦 Đất đang rất ẩm (").append(soilMoisture).append("%).\n");
                sb.append("→ Không cần tưới, tránh ngập úng rễ cây.");
            }

        } else if (q.contains("nhiệt") || q.contains("nóng") || q.contains("quạt")) {
            if (temperature > 38) {
                sb.append("🌡️ Nhiệt độ nguy hiểm (").append(temperature).append("°C)!\n");
                sb.append("→ Bật quạt ngay + điều chỉnh mái che.");
            } else if (temperature > 33) {
                sb.append("☀️ Nhiệt độ cao (").append(temperature).append("°C).\n");
                sb.append("→ Nên bật quạt để thông gió cho cây.");
            } else if (temperature > 25) {
                sb.append("🌤️ Nhiệt độ bình thường (").append(temperature).append("°C).\n");
                sb.append("→ Không cần can thiệp.");
            } else {
                sb.append("❄️ Nhiệt độ mát (").append(temperature).append("°C).\n");
                sb.append("→ Cây đang trong điều kiện tốt.");
            }

        } else if (q.contains("bình") || q.contains("mực nước") || q.contains("hết nước")) {
            if (waterLevel < 15) {
                sb.append("🚨 Bình nước gần cạn (").append(waterLevel).append("%)!\n");
                sb.append("→ Cần bổ sung nước NGAY, tắt bơm để tránh hỏng máy.");
            } else if (waterLevel < 30) {
                sb.append("⚠️ Nước còn ít (").append(waterLevel).append("%).\n");
                sb.append("→ Chuẩn bị bổ sung nước trong ngày hôm nay.");
            } else {
                sb.append("✅ Mực nước đủ (").append(waterLevel).append("%).\n");
                sb.append("→ Ổn định, không cần bổ sung.");
            }

        } else if (q.contains("phân tích") || q.contains("tình trạng") || q.contains("vườn")
                || q.contains("báo cáo") || q.contains("tổng quan")) {
            sb.append("📊 Tình trạng vườn hiện tại:\n\n");
            sb.append("🌡️ Nhiệt độ: ").append(temperature).append("°C ");
            sb.append(temperature > 33 ? "⚠️ Cao" : "✅ Bình thường").append("\n");
            sb.append("💧 Độ ẩm KK: ").append(humidity).append("% ");
            sb.append(humidity < 40 ? "⚠️ Khô" : "✅ Tốt").append("\n");
            sb.append("🌱 Độ ẩm đất: ").append(soilMoisture).append("% ");
            sb.append(soilMoisture < 30 ? "🚨 Cần tưới" : soilMoisture < 60 ? "⚠️ Theo dõi" : "✅ Tốt").append("\n");
            sb.append("🪣 Bình nước: ").append(waterLevel).append("% ");
            sb.append(waterLevel < 20 ? "🚨 Sắp hết" : "✅ Đủ").append("\n");
            sb.append("☀️ Ánh sáng: ").append(lightLevel).append("%\n");
            sb.append("🔧 Bơm: ").append(pumpOn ? "BẬT" : "TẮT").append(" | ");
            sb.append("Quạt: ").append(fanOn ? "BẬT" : "TẮT").append("\n\n");

            if (soilMoisture < 30 && waterLevel > 20) {
                sb.append("👉 Khuyến nghị: Tưới nước ngay!");
            } else if (waterLevel < 20) {
                sb.append("👉 Khuyến nghị: Bổ sung nước vào bình trước!");
            } else if (temperature > 33) {
                sb.append("👉 Khuyến nghị: Bật quạt thông gió.");
            } else {
                sb.append("👉 Vườn đang trong tình trạng tốt! 🌿");
            }

        } else if (q.contains("lịch") || q.contains("schedule") || q.contains("giờ tưới")) {
            sb.append("⏰ Lịch tưới gợi ý:\n");
            sb.append("• Sáng: 6:00 - 6:15 (15 phút)\n");
            sb.append("• Chiều: 17:00 - 17:10 (10 phút)\n\n");
            sb.append("💡 Tránh tưới lúc 10:00-15:00 (nắng gắt, nước bốc hơi nhanh).");

        } else if (q.contains("tự động") || q.contains("auto")) {
            sb.append("⚙️ Cài đặt chế độ tự động gợi ý:\n");
            sb.append("• Tưới khi độ ẩm đất < 35%\n");
            sb.append("• Dừng tưới khi đất đạt 70%\n");
            sb.append("• Bật quạt khi nhiệt độ > 33°C\n");
            sb.append("• Đóng mái che khi ánh sáng > 80%");

        } else if (q.contains("xin chào") || q.contains("hello") || q.contains("hi")
                || q.contains("chào")) {
            sb.append("👋 Xin chào! Tôi là AI trợ lý vườn thông minh.\n\n");
            sb.append("Tôi có thể giúp bạn:\n");
            sb.append("• 📊 Phân tích tình trạng vườn\n");
            sb.append("• 💡 Gợi ý khi nào cần tưới\n");
            sb.append("• ⏰ Lập lịch tưới tự động\n");
            sb.append("• 🔧 Điều khiển thiết bị (\"bật bơm\", \"tắt quạt\"...)\n\n");
            sb.append("Hãy hỏi tôi bất cứ điều gì về vườn của bạn!");

        } else {
            // Câu hỏi không khớp → phân tích tổng quát
            sb.append("🌿 Dựa trên dữ liệu hiện tại:\n");
            boolean allGood = true;
            if (soilMoisture < 30) { sb.append("• ⚠️ Đất đang khô, nên tưới\n"); allGood = false; }
            if (waterLevel < 20)   { sb.append("• 🚨 Nước gần hết, cần bổ sung\n"); allGood = false; }
            if (temperature > 33)  { sb.append("• ☀️ Nhiệt độ cao, nên bật quạt\n"); allGood = false; }
            if (allGood) sb.append("• ✅ Tất cả chỉ số đang tốt!\n");
            sb.append("\nBạn có thể hỏi: \"phân tích vườn\", \"có cần tưới không\", \"lịch tưới\", \"bật bơm\"...");
        }

        return sb.toString();
    }

    // =========================================================================
    // NHẬN DIỆN LỆNH ĐIỀU KHIỂN THIẾT BỊ
    // =========================================================================

    /**
     * Nhận diện lệnh điều khiển từ text chat.
     * @return chuỗi lệnh dạng "device:action" hoặc null nếu không phải lệnh
     */
    private String detectDeviceCommand(String text) {
        String t = text.toLowerCase().trim();

        // --- Bơm ---
        if ((t.contains("bật") || t.contains("mở") || t.contains("khởi động") || t.contains("on"))
                && (t.contains("bơm") || t.contains("pump") || t.contains("tưới ngay"))) {
            return "pump:ON";
        }
        if ((t.contains("tắt") || t.contains("dừng") || t.contains("stop") || t.contains("off"))
                && (t.contains("bơm") || t.contains("pump"))) {
            return "pump:OFF";
        }

        // --- Quạt ---
        if ((t.contains("bật") || t.contains("mở") || t.contains("on"))
                && (t.contains("quạt") || t.contains("fan"))) {
            return "fan:ON";
        }
        if ((t.contains("tắt") || t.contains("dừng") || t.contains("off"))
                && (t.contains("quạt") || t.contains("fan"))) {
            return "fan:OFF";
        }

        // --- Mái che / Servo ---
        if (t.contains("mở mái") || t.contains("mở che") || t.contains("kéo mái")) {
            return "servo:0";
        }
        if (t.contains("đóng mái") || t.contains("che mái") || t.contains("đóng che")) {
            return "servo:180";
        }
        if (t.contains("mái") || t.contains("che") || t.contains("servo")) {
            // Tìm số góc trong câu
            String angle = extractNumber(t);
            if (angle != null) return "servo:" + angle;
        }

        // --- Chế độ tự động ---
        if ((t.contains("bật") || t.contains("on") || t.contains("kích hoạt"))
                && (t.contains("tự động") || t.contains("auto"))) {
            return "automode:ON";
        }
        if ((t.contains("tắt") || t.contains("off") || t.contains("dừng"))
                && (t.contains("tự động") || t.contains("auto"))) {
            return "automode:OFF";
        }

        return null; // Không phải lệnh điều khiển
    }

    /** Trích xuất số đầu tiên tìm thấy trong chuỗi */
    private String extractNumber(String text) {
        StringBuilder num = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.isDigit(c)) {
                num.append(c);
            } else if (num.length() > 0) {
                break;
            }
        }
        return num.length() > 0 ? num.toString() : null;
    }

    /** Thực thi lệnh điều khiển và phản hồi người dùng */
    private void executeDeviceCommand(String command, String originalText, AiCallback callback) {
        String[] parts = command.split(":");
        if (parts.length != 2) return;

        String device = parts[0];
        String action = parts[1];

        // Gọi callback để MainActivity publish MQTT
        if (deviceCommandCallback != null) {
            mainHandler.post(() -> deviceCommandCallback.onDeviceCommand(device, action));
        }

        // Phản hồi xác nhận cho user
        String response = buildCommandConfirmation(device, action);
        chatHistory.add(new ChatMessage("model", response));
        mainHandler.post(() -> callback.onResponse(response));
    }

    private String buildCommandConfirmation(String device, String action) {
        boolean isOn = action.equals("ON");
        switch (device) {
            case "pump":
                return (isOn ? "✅ Đã bật bơm nước!" : "✅ Đã tắt bơm nước!")
                        + "\n→ Lệnh đã gửi đến ESP32.";
            case "fan":
                return (isOn ? "✅ Đã bật quạt!" : "✅ Đã tắt quạt!")
                        + "\n→ Lệnh đã gửi đến ESP32.";
            case "servo":
                return "✅ Đã điều chỉnh mái che về " + action + "°\n→ Lệnh đã gửi đến ESP32.";
            case "automode":
                return (isOn
                        ? "✅ Đã bật chế độ tự động!\n→ ESP32 sẽ tự điều khiển theo ngưỡng cài sẵn."
                        : "✅ Đã tắt chế độ tự động!\n→ Bạn có thể điều khiển thủ công.");
            default:
                return "✅ Lệnh đã được gửi!";
        }
    }

    // =========================================================================
    // SYSTEM PROMPT cho Gemini
    // =========================================================================

    private String buildSystemPrompt() {
        return "Bạn là trợ lý AI thông minh cho hệ thống vườn IoT.\n"
                + "Trả lời NGẮN GỌN, rõ ràng, bằng tiếng Việt, dùng emoji phù hợp.\n"
                + "Không dài dòng, tối đa 150 từ mỗi câu trả lời.\n\n"
                + "Dữ liệu vườn HIỆN TẠI:\n"
                + "- Nhiệt độ: " + temperature + "°C\n"
                + "- Độ ẩm không khí: " + humidity + "%\n"
                + "- Độ ẩm đất: " + soilMoisture + "%\n"
                + "- Mực nước bình: " + waterLevel + "%\n"
                + "- Ánh sáng: " + lightLevel + "%\n"
                + "- Bơm: " + (pumpOn ? "BẬT" : "TẮT") + "\n"
                + "- Quạt: " + (fanOn ? "BẬT" : "TẮT") + "\n"
                + "- Chế độ tự động: " + (autoMode ? "BẬT" : "TẮT") + "\n\n"
                + "Lưu ý: Nếu user muốn điều khiển thiết bị (bật/tắt bơm, quạt...), "
                + "hãy xác nhận và giải thích ngắn gọn tại sao nên/không nên làm vậy "
                + "dựa trên dữ liệu cảm biến hiện tại.";
    }

    // =========================================================================
    // TIỆN ÍCH
    // =========================================================================

    /** Xoá lịch sử hội thoại (bắt đầu cuộc trò chuyện mới) */
    public void clearHistory() {
        chatHistory.clear();
    }

    /** Lấy danh sách lịch sử chat để hiển thị UI */
    public List<ChatMessage> getChatHistory() {
        return new ArrayList<>(chatHistory);
    }
}
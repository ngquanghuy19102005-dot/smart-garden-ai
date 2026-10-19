package com.goatbet88.iot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * GardenNotificationHelper
 * Gửi thông báo hệ thống (xuất hiện ngay cả khi app đóng).
 *
 * Có 2 kênh:
 *   - CHANNEL_ALERT  : cảnh báo khẩn (đất khô, hết nước) — có âm thanh + rung
 *   - CHANNEL_INFO   : thông tin (lịch tưới xong, kết nối lại) — im lặng
 */
public class GardenNotificationHelper {

    private static final String CHANNEL_ALERT = "garden_alert";
    private static final String CHANNEL_INFO  = "garden_info";

    // ID thông báo (mỗi loại 1 ID cố định để update thay vì tạo mới)
    public static final int NOTIF_SOIL_DRY    = 1001;
    public static final int NOTIF_WATER_LOW   = 1002;
    public static final int NOTIF_TEMP_HIGH   = 1003;
    public static final int NOTIF_MQTT_LOST   = 1004;
    public static final int NOTIF_SCHEDULE    = 1005;

    private final Context context;
    private final NotificationManagerCompat nm;

    public GardenNotificationHelper(Context context) {
        this.context = context.getApplicationContext();
        this.nm      = NotificationManagerCompat.from(this.context);
        createChannels();
    }

    // ─── Tạo kênh thông báo (bắt buộc từ Android 8+) ─────────────
    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        // Kênh cảnh báo — quan trọng cao, có âm thanh
        NotificationChannel alertChannel = new NotificationChannel(
                CHANNEL_ALERT, "Cảnh báo vườn", NotificationManager.IMPORTANCE_HIGH);
        alertChannel.setDescription("Cảnh báo khẩn về tình trạng vườn");
        alertChannel.enableVibration(true);
        alertChannel.setShowBadge(true);
        manager.createNotificationChannel(alertChannel);

        // Kênh thông tin — im lặng
        NotificationChannel infoChannel = new NotificationChannel(
                CHANNEL_INFO, "Thông tin vườn", NotificationManager.IMPORTANCE_LOW);
        infoChannel.setDescription("Thông báo lịch tưới và kết nối");
        infoChannel.setShowBadge(false);
        manager.createNotificationChannel(infoChannel);
    }

    // ─── Cảnh báo đất khô ─────────────────────────────────────────
    public void notifySoilDry(int soilPercent) {
        String title = "🌱 Đất đang khô!";
        String body  = soilPercent < 20
                ? "Độ ẩm đất chỉ còn " + soilPercent + "% — cần tưới NGAY!"
                : "Độ ẩm đất " + soilPercent + "% — nên tưới sớm.";
        sendAlert(NOTIF_SOIL_DRY, title, body);
    }

    // ─── Cảnh báo bình nước sắp hết ──────────────────────────────
    public void notifyWaterLow(int waterPercent) {
        sendAlert(NOTIF_WATER_LOW,
                "🚰 Bình nước sắp hết!",
                "Mực nước chỉ còn " + waterPercent + "% — cần bổ sung nước.");
    }

    // ─── Cảnh báo nhiệt độ cao ────────────────────────────────────
    public void notifyTempHigh(float temp) {
        sendAlert(NOTIF_TEMP_HIGH,
                "🌡️ Nhiệt độ quá cao!",
                "Nhiệt độ hiện tại " + temp + "°C — nên bật quạt thông gió.");
    }

    // ─── Thông báo mất kết nối MQTT ──────────────────────────────
    public void notifyMqttLost() {
        sendInfo(NOTIF_MQTT_LOST,
                "📡 Mất kết nối",
                "Đang thử kết nối lại với ESP32...");
    }

    // ─── Hủy thông báo mất kết nối khi đã kết nối lại ───────────
    public void cancelMqttLost() {
        nm.cancel(NOTIF_MQTT_LOST);
    }

    // ─── Thông báo lịch tưới hoàn thành ──────────────────────────
    public void notifyScheduleDone(int durationMin) {
        sendInfo(NOTIF_SCHEDULE,
                "⏰ Lịch tưới hoàn thành",
                "Đã tưới " + durationMin + " phút. Vườn đã được tưới đủ nước.");
    }

    // ─── Thông báo lịch tưới bắt đầu ─────────────────────────────
    public void notifyScheduleStart(String timeStr, int durationMin) {
        sendInfo(NOTIF_SCHEDULE,
                "⏰ Đang tưới tự động",
                "Lịch " + timeStr + " — tưới trong " + durationMin + " phút.");
    }

    // ─── Helper gửi thông báo cảnh báo (âm thanh + rung) ─────────
    private void sendAlert(int id, String title, String body) {
        PendingIntent pi = buildOpenAppIntent();
        Notification notif = new NotificationCompat.Builder(context, CHANNEL_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(id, notif);
        } catch (SecurityException e) {
            // Chưa cấp quyền POST_NOTIFICATIONS (Android 13+) — bỏ qua
        }
    }

    // ─── Helper gửi thông báo thông tin (im lặng) ─────────────────
    private void sendInfo(int id, String title, String body) {
        PendingIntent pi = buildOpenAppIntent();
        Notification notif = new NotificationCompat.Builder(context, CHANNEL_INFO)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(id, notif);
        } catch (SecurityException e) {
            // Chưa cấp quyền — bỏ qua
        }
    }

    // ─── Mở app khi bấm vào thông báo ────────────────────────────
    private PendingIntent buildOpenAppIntent() {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
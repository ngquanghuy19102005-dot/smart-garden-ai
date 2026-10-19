package com.goatbet88.iot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * WateringAlarmReceiver
 * Nhận alarm từ AlarmManager → gọi callback để MainActivity publish MQTT.
 * Hoạt động cả khi app đóng hoàn toàn.
 */
public class WateringAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "WateringReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action    = intent.getAction();
        int scheduleId   = intent.getIntExtra(WateringScheduleManager.EXTRA_SCHEDULE_ID, -1);
        int durationSec  = intent.getIntExtra(WateringScheduleManager.EXTRA_DURATION_SEC, 60);

        Log.d(TAG, "onReceive action=" + action + " scheduleId=" + scheduleId);

        WateringScheduleManager manager = new WateringScheduleManager(context);

        if (WateringScheduleManager.ACTION_WATER_START.equals(action)) {

            // 1. Gọi callback để bật bơm qua MQTT
            WateringScheduleManager.OnTriggerListener listener =
                    WateringScheduleManager.getTriggerListener();
            if (listener != null) {
                listener.onWaterStart(durationSec);
            } else {
                // App đang đóng → khởi động MainActivity để publish MQTT
                Intent launchIntent = new Intent(context, MainActivity.class);
                launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                launchIntent.putExtra("auto_pump_on", true);
                launchIntent.putExtra("auto_duration", durationSec);
                context.startActivity(launchIntent);
            }

            // 2. Lên lịch tắt bơm sau durationSec giây
            WateringScheduleManager.WateringSchedule s = manager.findById(scheduleId);
            if (s != null) {
                manager.scheduleStop(s);
                // Lên lịch lần kích hoạt tiếp theo (tuần sau)
                manager.rescheduleNext(s);
            }

        } else if (WateringScheduleManager.ACTION_WATER_STOP.equals(action)) {

            WateringScheduleManager.OnTriggerListener listener =
                    WateringScheduleManager.getTriggerListener();
            if (listener != null) {
                listener.onWaterStop();
            } else {
                Intent launchIntent = new Intent(context, MainActivity.class);
                launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                launchIntent.putExtra("auto_pump_off", true);
                context.startActivity(launchIntent);
            }
        }

        // 3. Xử lý khởi động lại máy → restore tất cả alarm
        else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            manager.restoreAllAlarms();
            Log.d(TAG, "Boot completed - restored all watering alarms");
        }
    }
}
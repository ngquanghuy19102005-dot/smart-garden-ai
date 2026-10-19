package com.goatbet88.iot;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * WateringScheduleManager
 * Quản lý lịch tưới tự động bằng AlarmManager.
 * Hoạt động cả khi app đóng hoặc màn hình tắt.
 */
public class WateringScheduleManager {

    private static final String TAG = "WateringSchedule";
    private static final String PREFS_NAME = "watering_schedules";
    private static final String KEY_SCHEDULES = "schedules_json";

    // Action cho BroadcastReceiver
    public static final String ACTION_WATER_START = "com.goatbet88.iot.WATER_START";
    public static final String ACTION_WATER_STOP  = "com.goatbet88.iot.WATER_STOP";

    // Extra keys
    public static final String EXTRA_SCHEDULE_ID  = "schedule_id";
    public static final String EXTRA_DURATION_SEC = "duration_sec";

    private final Context context;
    private final AlarmManager alarmManager;
    private final List<WateringSchedule> schedules = new ArrayList<>();

    // Callback để MainActivity publish MQTT
    public interface OnTriggerListener {
        void onWaterStart(int durationSeconds);
        void onWaterStop();
    }

    private static OnTriggerListener triggerListener;

    public static void setTriggerListener(OnTriggerListener l) {
        triggerListener = l;
    }

    public static OnTriggerListener getTriggerListener() {
        return triggerListener;
    }

    // ─── Model ────────────────────────────────────────────────────
    public static class WateringSchedule {
        public int    id;
        public int    hour;
        public int    minute;
        public int    durationSeconds;   // thời gian tưới (giây)
        public boolean[] days;           // days[0]=Chủ nhật, days[1]=Thứ 2... days[6]=Thứ 7
        public boolean   enabled;

        public WateringSchedule(int id, int hour, int minute, int durationSeconds, boolean[] days) {
            this.id              = id;
            this.hour            = hour;
            this.minute          = minute;
            this.durationSeconds = durationSeconds;
            this.days            = days;
            this.enabled         = true;
        }

        public String getTimeDisplay() {
            return String.format("%02d:%02d", hour, minute);
        }

        public String getDaysDisplay() {
            String[] names = {"CN", "T2", "T3", "T4", "T5", "T6", "T7"};
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 7; i++) {
                if (days[i]) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(names[i]);
                }
            }
            return sb.length() > 0 ? sb.toString() : "Không lặp";
        }

        public String getDurationDisplay() {
            if (durationSeconds < 60) return durationSeconds + " giây";
            return (durationSeconds / 60) + " phút";
        }

        // Serialization
        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("hour", hour);
            o.put("minute", minute);
            o.put("durationSeconds", durationSeconds);
            o.put("enabled", enabled);
            JSONArray da = new JSONArray();
            for (boolean d : days) da.put(d);
            o.put("days", da);
            return o;
        }

        public static WateringSchedule fromJson(JSONObject o) throws Exception {
            JSONArray da = o.getJSONArray("days");
            boolean[] days = new boolean[7];
            for (int i = 0; i < 7; i++) days[i] = da.getBoolean(i);
            WateringSchedule s = new WateringSchedule(
                    o.getInt("id"), o.getInt("hour"), o.getInt("minute"),
                    o.getInt("durationSeconds"), days);
            s.enabled = o.optBoolean("enabled", true);
            return s;
        }
    }

    // ─── Constructor ──────────────────────────────────────────────
    public WateringScheduleManager(Context context) {
        this.context      = context.getApplicationContext();
        this.alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        loadSchedules();
    }

    // ─── CRUD ─────────────────────────────────────────────────────
    public WateringSchedule addSchedule(int hour, int minute, int durationSec, boolean[] days) {
        int id = (int) (System.currentTimeMillis() % 100000);
        WateringSchedule s = new WateringSchedule(id, hour, minute, durationSec, days);
        schedules.add(s);
        saveSchedules();
        scheduleAlarm(s);
        return s;
    }

    public void removeSchedule(int scheduleId) {
        for (WateringSchedule s : schedules) {
            if (s.id == scheduleId) {
                cancelAlarm(s);
                schedules.remove(s);
                saveSchedules();
                return;
            }
        }
    }

    public void setEnabled(int scheduleId, boolean enabled) {
        for (WateringSchedule s : schedules) {
            if (s.id == scheduleId) {
                s.enabled = enabled;
                if (enabled) scheduleAlarm(s);
                else         cancelAlarm(s);
                saveSchedules();
                return;
            }
        }
    }

    public List<WateringSchedule> getSchedules() {
        return new ArrayList<>(schedules);
    }

    // ─── AlarmManager ─────────────────────────────────────────────
    private void scheduleAlarm(WateringSchedule s) {
        if (!s.enabled) return;

        boolean hasAnyDay = false;
        for (boolean d : s.days) if (d) { hasAnyDay = true; break; }

        if (hasAnyDay) {
            // Lặp theo từng ngày trong tuần
            for (int dayOfWeek = Calendar.SUNDAY; dayOfWeek <= Calendar.SATURDAY; dayOfWeek++) {
                if (s.days[dayOfWeek - 1]) {
                    scheduleForDayOfWeek(s, dayOfWeek);
                }
            }
        } else {
            // Chạy 1 lần vào ngày hôm sau nếu giờ đã qua
            scheduleSingleShot(s);
        }
    }

    private void scheduleForDayOfWeek(WateringSchedule s, int dayOfWeek) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_WEEK, dayOfWeek);
        cal.set(Calendar.HOUR_OF_DAY, s.hour);
        cal.set(Calendar.MINUTE, s.minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        // Nếu thời gian đã qua trong tuần này → lùi sang tuần sau
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.WEEK_OF_YEAR, 1);
        }

        PendingIntent pi = buildStartIntent(s, dayOfWeek);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        } else {
            alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        }

        Log.d(TAG, "Scheduled alarm: id=" + s.id + " day=" + dayOfWeek
                + " time=" + s.getTimeDisplay());
    }

    private void scheduleSingleShot(WateringSchedule s) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, s.hour);
        cal.set(Calendar.MINUTE, s.minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }

        PendingIntent pi = buildStartIntent(s, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        } else {
            alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        }
    }

    private void cancelAlarm(WateringSchedule s) {
        for (int day = 0; day <= 6; day++) {
            PendingIntent pi = buildStartIntent(s, day);
            alarmManager.cancel(pi);
        }
        // Hủy cả stop alarm
        PendingIntent stopPi = buildStopIntent(s);
        alarmManager.cancel(stopPi);
        Log.d(TAG, "Cancelled alarms for schedule id=" + s.id);
    }

    private PendingIntent buildStartIntent(WateringSchedule s, int dayOfWeek) {
        Intent intent = new Intent(context, WateringAlarmReceiver.class);
        intent.setAction(ACTION_WATER_START);
        intent.putExtra(EXTRA_SCHEDULE_ID, s.id);
        intent.putExtra(EXTRA_DURATION_SEC, s.durationSeconds);
        int requestCode = s.id * 10 + dayOfWeek;
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent buildStopIntent(WateringSchedule s) {
        Intent intent = new Intent(context, WateringAlarmReceiver.class);
        intent.setAction(ACTION_WATER_STOP);
        intent.putExtra(EXTRA_SCHEDULE_ID, s.id);
        return PendingIntent.getBroadcast(context, s.id * 10 + 99, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Gọi hàm này sau khi bơm bắt đầu để lên lịch tắt bơm sau duration giây.
     */
    public void scheduleStop(WateringSchedule s) {
        Intent intent = new Intent(context, WateringAlarmReceiver.class);
        intent.setAction(ACTION_WATER_STOP);
        intent.putExtra(EXTRA_SCHEDULE_ID, s.id);
        PendingIntent pi = PendingIntent.getBroadcast(context, s.id * 10 + 99, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long stopAt = System.currentTimeMillis() + s.durationSeconds * 1000L;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, stopAt, pi);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, stopAt, pi);
        }
    }

    // ─── Reschedule lại tuần sau sau khi alarm kích hoạt ──────────
    public void rescheduleNext(WateringSchedule s) {
        if (s.enabled) scheduleAlarm(s);
    }

    public WateringSchedule findById(int id) {
        for (WateringSchedule s : schedules) {
            if (s.id == id) return s;
        }
        return null;
    }

    // ─── Persistence ──────────────────────────────────────────────
    private void saveSchedules() {
        try {
            JSONArray arr = new JSONArray();
            for (WateringSchedule s : schedules) arr.put(s.toJson());
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_SCHEDULES, arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "Save error", e);
        }
    }

    private void loadSchedules() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_SCHEDULES, "[]");
            JSONArray arr = new JSONArray(json);
            schedules.clear();
            for (int i = 0; i < arr.length(); i++) {
                schedules.add(WateringSchedule.fromJson(arr.getJSONObject(i)));
            }
            Log.d(TAG, "Loaded " + schedules.size() + " schedules");
        } catch (Exception e) {
            Log.e(TAG, "Load error", e);
        }
    }

    // ─── Re-register tất cả alarm sau khi máy khởi động lại ──────
    public void restoreAllAlarms() {
        for (WateringSchedule s : schedules) {
            if (s.enabled) scheduleAlarm(s);
        }
        Log.d(TAG, "Restored " + schedules.size() + " alarms after reboot");
    }
}
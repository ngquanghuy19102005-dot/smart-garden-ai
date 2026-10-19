package com.goatbet88.iot;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * ScheduleDialogHelper
 * Dialog để người dùng thêm / xem / xóa lịch tưới.
 * Gọi showManageDialog() từ MainActivity khi bấm nút "Cài lịch".
 */
public class ScheduleDialogHelper {

    private final Context context;
    private final WateringScheduleManager manager;
    private Runnable onChanged; // callback cập nhật UI sau khi thêm/xóa

    public ScheduleDialogHelper(Context context, WateringScheduleManager manager) {
        this.context = context;
        this.manager = manager;
    }

    public void setOnChangedListener(Runnable r) {
        this.onChanged = r;
    }

    // ─── Dialog chính: danh sách lịch + nút thêm ─────────────────
    public void showManageDialog() {
        List<WateringScheduleManager.WateringSchedule> schedules = manager.getSchedules();

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("⏰ Lịch tưới tự động");

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(8), pad, dp(8));

        if (schedules.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("Chưa có lịch nào. Nhấn \"Thêm lịch\" để tạo mới.");
            empty.setTextColor(0xFF9E9E9E);
            empty.setPadding(0, dp(8), 0, dp(8));
            root.addView(empty);
        } else {
            for (WateringScheduleManager.WateringSchedule s : schedules) {
                root.addView(buildScheduleRow(s));
            }
        }

        builder.setView(root);
        builder.setPositiveButton("+ Thêm lịch", (d, w) -> showAddDialog());
        builder.setNegativeButton("Đóng", null);
        builder.show();
    }

    // ─── Row hiển thị 1 lịch ─────────────────────────────────────
    private View buildScheduleRow(WateringScheduleManager.WateringSchedule s) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        row.setLayoutParams(lp);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackgroundColor(0xFFF5F5F5);

        // Info
        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvTime = new TextView(context);
        tvTime.setText("🕐 " + s.getTimeDisplay() + "  ⏱ " + s.getDurationDisplay());
        tvTime.setTextSize(15f);
        tvTime.setTextColor(0xFF212121);
        info.addView(tvTime);

        TextView tvDays = new TextView(context);
        tvDays.setText("📅 " + s.getDaysDisplay());
        tvDays.setTextSize(12f);
        tvDays.setTextColor(0xFF757575);
        info.addView(tvDays);

        row.addView(info);

        // Toggle enable
        Switch sw = new Switch(context);
        sw.setChecked(s.enabled);
        sw.setOnCheckedChangeListener((b, checked) -> {
            manager.setEnabled(s.id, checked);
            Toast.makeText(context,
                    checked ? "✅ Đã bật lịch " + s.getTimeDisplay()
                            : "⏸ Đã tắt lịch " + s.getTimeDisplay(),
                    Toast.LENGTH_SHORT).show();
            notifyChanged();
        });
        row.addView(sw);

        // Nút xóa
        TextView btnDel = new TextView(context);
        btnDel.setText(" 🗑 ");
        btnDel.setTextSize(18f);
        btnDel.setPadding(dp(8), 0, 0, 0);
        btnDel.setOnClickListener(v -> confirmDelete(s));
        row.addView(btnDel);

        return row;
    }

    // ─── Dialog thêm lịch mới ─────────────────────────────────────
    public void showAddDialog() {
        final int[] selectedHour   = {6};
        final int[] selectedMinute = {0};
        final int[] durationMin    = {10}; // mặc định 10 phút
        final boolean[] days       = {false, true, true, true, true, true, false}; // T2-T6

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Thêm lịch tưới mới");

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(12), pad, dp(8));

        // ── Chọn giờ ──
        TextView tvTimeLbl = new TextView(context);
        tvTimeLbl.setText("⏰ Giờ tưới");
        tvTimeLbl.setTextSize(13f);
        tvTimeLbl.setTextColor(0xFF9E9E9E);
        root.addView(tvTimeLbl);

        TextView tvTimeVal = new TextView(context);
        tvTimeVal.setText(String.format("  %02d:%02d", selectedHour[0], selectedMinute[0]));
        tvTimeVal.setTextSize(28f);
        tvTimeVal.setTextColor(0xFF2E7D32);
        tvTimeVal.setOnClickListener(v -> {
            TimePickerDialog tpd = new TimePickerDialog(context, (tp, h, m) -> {
                selectedHour[0]   = h;
                selectedMinute[0] = m;
                tvTimeVal.setText(String.format("  %02d:%02d", h, m));
            }, selectedHour[0], selectedMinute[0], true);
            tpd.show();
        });
        root.addView(tvTimeVal);

        // ── Chọn thời lượng ──
        addSpacer(root, dp(12));
        TextView tvDurLbl = new TextView(context);
        tvDurLbl.setText("⏱ Thời lượng tưới: " + durationMin[0] + " phút");
        tvDurLbl.setTextSize(13f);
        tvDurLbl.setTextColor(0xFF9E9E9E);
        root.addView(tvDurLbl);

        SeekBar seekDur = new SeekBar(context);
        seekDur.setMax(59);   // 1-60 phút
        seekDur.setProgress(durationMin[0] - 1);
        seekDur.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                durationMin[0] = p + 1;
                tvDurLbl.setText("⏱ Thời lượng tưới: " + durationMin[0] + " phút");
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s)  {}
        });
        root.addView(seekDur);

        // ── Chọn ngày ──
        addSpacer(root, dp(12));
        TextView tvDaysLbl = new TextView(context);
        tvDaysLbl.setText("📅 Lặp lại vào:");
        tvDaysLbl.setTextSize(13f);
        tvDaysLbl.setTextColor(0xFF9E9E9E);
        root.addView(tvDaysLbl);

        String[] dayNames = {"CN", "T2", "T3", "T4", "T5", "T6", "T7"};
        LinearLayout daysRow = new LinearLayout(context);
        daysRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 7; i++) {
            final int idx = i;
            CheckBox cb = new CheckBox(context);
            cb.setText(dayNames[i]);
            cb.setChecked(days[i]);
            cb.setOnCheckedChangeListener((b, c) -> days[idx] = c);
            daysRow.addView(cb);
        }
        root.addView(daysRow);

        builder.setView(root);
        builder.setPositiveButton("✅ Lưu lịch", (d, w) -> {
            int durationSec = durationMin[0] * 60;
            WateringScheduleManager.WateringSchedule s =
                    manager.addSchedule(selectedHour[0], selectedMinute[0], durationSec, days);
            Toast.makeText(context,
                    "✅ Đã thêm lịch tưới lúc " + s.getTimeDisplay(),
                    Toast.LENGTH_SHORT).show();
            notifyChanged();
        });
        builder.setNegativeButton("Hủy", null);
        builder.show();
    }

    // ─── Xác nhận xóa ────────────────────────────────────────────
    private void confirmDelete(WateringScheduleManager.WateringSchedule s) {
        new AlertDialog.Builder(context)
                .setTitle("Xóa lịch tưới?")
                .setMessage("Lịch tưới lúc " + s.getTimeDisplay() + " sẽ bị xóa vĩnh viễn.")
                .setPositiveButton("Xóa", (d, w) -> {
                    manager.removeSchedule(s.id);
                    Toast.makeText(context, "🗑 Đã xóa lịch " + s.getTimeDisplay(),
                            Toast.LENGTH_SHORT).show();
                    notifyChanged();
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void notifyChanged() {
        if (onChanged != null) onChanged.run();
    }

    private void addSpacer(ViewGroup parent, int height) {
        View v = new View(context);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height));
        parent.addView(v);
    }

    private int dp(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
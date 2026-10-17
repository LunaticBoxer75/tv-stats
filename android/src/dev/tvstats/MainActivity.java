package dev.tvstats;

import android.app.Activity;
import android.app.AppOpsManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.time.format.TextStyle;
import java.util.Date;
import java.util.Locale;

/** Full-screen dashboard: the same table as tvstats.py, refreshed every 5 seconds. */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1116);
        root.setPadding(dp(56), dp(20), dp(56), dp(12));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(this::refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private void refresh() {
        root.removeAllViews();
        if (!hasUsageAccess() || !Settings.canDrawOverlays(this)) {
            root.addView(text("Watch time needs two permissions. From a computer on the same network, run:", 18, 0xFFFFFFFF, false));
            root.addView(text("\nadb shell appops set dev.tvstats GET_USAGE_STATS allow"
                    + "\nadb shell appops set dev.tvstats SYSTEM_ALERT_WINDOW allow", 16, 0xFF9BD1FF, true));
            if (hasUsageAccess()) addTable();
            handler.postDelayed(this::refresh, 5000);
            return;
        }
        HomePanelService.start(this);
        addTable();
        handler.postDelayed(this::refresh, 5000);
    }

    private void addTable() {
        Stats s = Stats.load(this);
        root.addView(text("Watch time", 24, 0xFFFFFFFF, false));
        root.addView(row("App", "Today", "Last 7 days", "All time", 0xFFFFFFFF, true));
        boolean totalShown = false;
        for (String pkg : s.ordered()) {
            if (s.isSystem(pkg) && !totalShown) {
                root.addView(row("Apps total", Stats.fmt(s.appTotal(s.today)), Stats.fmt(s.appTotal(s.week)),
                        Stats.fmt(s.appTotal(s.all)), 0xFFFFFFFF, true));
                totalShown = true;
            }
            int color = s.isSystem(pkg) ? 0x77FFFFFF : 0xDDFFFFFF;
            root.addView(row(Stats.label(this, pkg), Stats.fmt(s.today.getOrDefault(pkg, 0L)),
                    Stats.fmt(s.week.getOrDefault(pkg, 0L)), Stats.fmt(s.all.getOrDefault(pkg, 0L)), color, false));
        }
        if (!totalShown) {
            root.addView(row("Apps total", Stats.fmt(s.appTotal(s.today)), Stats.fmt(s.appTotal(s.week)),
                    Stats.fmt(s.appTotal(s.all)), 0xFFFFFFFF, true));
        }

        root.addView(text("Last 7 days (apps)", 16, 0xFFFFFFFF, false));
        long peak = 1;
        for (long d : s.perDay) peak = Math.max(peak, d);
        for (int i = 0; i < 7; i++) {
            String day = s.days[i].getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    + " " + s.days[i].getMonthValue() + "/" + s.days[i].getDayOfMonth();
            StringBuilder bar = new StringBuilder();
            for (int j = 0; j < Math.round(30.0 * s.perDay[i] / peak); j++) bar.append('█');
            root.addView(text(String.format("%-10s %8s  %s", day, Stats.fmt(s.perDay[i]), bar), 13, 0xCCFFFFFF, true));
        }
        if (s.since != Long.MAX_VALUE) {
            root.addView(text("Tracking since " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(new Date(s.since)) + " (start of the TV's yearly record).", 12, 0x77FFFFFF, false));
        }
    }

    private LinearLayout row(String app, String today, String week, String all, int color, boolean bold) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(1), 0, dp(1));
        TextView name = text(app, 15, color, false);
        name.setSingleLine(true);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3));
        for (String value : new String[] {today, week, all}) {
            TextView cell = text(value, 15, color, false);
            cell.setGravity(Gravity.END);
            row.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        if (bold) for (int i = 0; i < row.getChildCount(); i++) ((TextView) row.getChildAt(i)).setTypeface(null, Typeface.BOLD);
        return row;
    }

    private TextView text(String value, int sp, int color, boolean mono) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (mono) view.setTypeface(Typeface.MONOSPACE);
        return view;
    }

    private boolean hasUsageAccess() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

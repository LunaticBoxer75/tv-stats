package dev.tvstats;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shows a small watch-time card in the top-right corner, under the clock, only while the TV's Home screen is in front. */
public final class HomePanelService extends Service {
    private static final long POLL_MS = 500, REDRAW_MS = 30_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private UsageStatsManager usage;
    private LinearLayout panel;
    private String home, front;
    private long checkedUntil, drawnAt;
    private boolean showing;

    static void start(Context context) {
        context.startForegroundService(new Intent(context, HomePanelService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("panel", "Home panel", NotificationManager.IMPORTANCE_MIN));
        startForeground(1, new Notification.Builder(this, "panel")
                .setSmallIcon(android.R.drawable.ic_menu_recent_history).setContentTitle("Watch time panel").build());
        windows = getSystemService(WindowManager.class);
        usage = getSystemService(UsageStatsManager.class);
        home = Stats.homePackage(this);
        checkedUntil = System.currentTimeMillis() - 24 * 3600_000L;
        handler.post(this::tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        UsageEvents events = usage.queryEvents(checkedUntil, now);
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.getNextEvent(e)) {
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) front = e.getPackageName();
        }
        checkedUntil = now;
        boolean onHome = home.equals(front) && Settings.canDrawOverlays(this);
        if (onHome && (!showing || now - drawnAt > REDRAW_MS)) draw(now);
        if (!onHome && showing) {
            windows.removeView(panel);
            showing = false;
        }
        handler.postDelayed(this::tick, POLL_MS);
    }

    private void draw(long now) {
        Stats stats = Stats.load(this);
        if (panel == null) {
            panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable card = new GradientDrawable();
            card.setColor(0xD9141820);
            card.setCornerRadius(dp(10));
            panel.setBackground(card);
            panel.setPadding(dp(12), dp(8), dp(12), dp(8));
        }
        panel.removeAllViews();
        panel.addView(row("Watch time", "", 9, 0x99FFFFFF));
        panel.addView(row("Today", Stats.fmt(stats.appTotal(stats.today)), 13, 0xFFFFFFFF));
        for (String pkg : stats.topToday(3)) {
            panel.addView(row(Stats.label(this, pkg), Stats.fmt(stats.today.get(pkg)), 10, 0xCCFFFFFF));
        }
        panel.addView(row("Last 7 days", Stats.fmt(stats.appTotal(stats.week)), 10, 0x99FFFFFF));
        if (!showing) {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    dp(170), WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // Never takes remote focus or input from the Home screen underneath.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            // Under the clock, over the banner image: no selectable Home items there.
            params.gravity = Gravity.TOP | Gravity.END;
            params.x = dp(56);
            params.y = dp(84);
            windows.addView(panel, params);
            showing = true;
        }
        drawnAt = now;
    }

    private LinearLayout row(String left, String right, int sp, int color) {
        LinearLayout row = new LinearLayout(this);
        TextView name = text(left, sp, color), time = text(right, sp, color);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(time);
        return row;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (showing) windows.removeView(panel);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

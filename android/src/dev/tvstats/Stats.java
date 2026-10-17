package dev.tvstats;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Watch time from the TV's usage log, counted the same way as tvstats.py. */
final class Stats {
    // Time on screen that is not watching something: listed apart, left out of totals.
    private static final Set<String> SYSTEM = new HashSet<>(Arrays.asList(
            "com.google.android.apps.tv.dreamx", "com.android.tv.settings", "com.android.vending",
            "com.google.android.katniss", "com.android.systemui", "com.google.android.permissioncontroller",
            "com.google.android.tungsten.setupwraith", "android", "dev.tvstats"));

    final Map<String, Long> today = new HashMap<>(), week = new HashMap<>(), all = new HashMap<>();
    final long[] perDay = new long[7];   // apps only, oldest first
    final LocalDate[] days = new LocalDate[7];
    long since = Long.MAX_VALUE;
    private final Set<String> system = new HashSet<>(SYSTEM);
    private final long[] dayStart = new long[8];

    static String homePackage(Context context) {
        ResolveInfo home = context.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return home == null ? "" : home.activityInfo.packageName;
    }

    static Stats load(Context context) {
        Stats s = new Stats();
        s.system.add(homePackage(context));
        UsageStatsManager usm = context.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate first = LocalDate.now(zone).minusDays(6);
        for (int i = 0; i < 8; i++) {
            if (i < 7) s.days[i] = first.plusDays(i);
            s.dayStart[i] = first.plusDays(i).atStartOfDay(zone).toInstant().toEpochMilli();
        }

        // An app is in front from its first resumed screen until the screen in front pauses.
        // Start 12 hours early so a session already running at the first midnight is found.
        UsageEvents events = usm.queryEvents(s.dayStart[0] - 12 * 3600_000L, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String fg = null, shown = null;
        long start = 0;
        while (events.getNextEvent(e)) {
            int type = e.getEventType();
            if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                if (!e.getPackageName().equals(fg)) {
                    if (fg != null) s.add(fg, start, e.getTimeStamp());
                    fg = e.getPackageName();
                    start = e.getTimeStamp();
                }
                shown = e.getClassName();
            } else if (type == UsageEvents.Event.ACTIVITY_PAUSED && e.getPackageName().equals(fg)
                    && e.getClassName() != null && e.getClassName().equals(shown)) {
                s.add(fg, start, e.getTimeStamp());
                fg = null;
            } else if (type == UsageEvents.Event.DEVICE_SHUTDOWN && fg != null) {
                s.add(fg, start, e.getTimeStamp());
                fg = null;
            }
        }
        if (fg != null) {
            s.add(fg, start, now);
        }

        // All time: the yearly buckets, which start when the TV was set up. Android adds the
        // current session only when the app closes, so add it here.
        List<UsageStats> yearly = usm.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY, 0, now);
        for (UsageStats u : yearly) {
            s.all.merge(u.getPackageName(), u.getTotalTimeInForeground(), Long::sum);
            s.since = Math.min(s.since, u.getFirstTimeStamp());
        }
        if (fg != null) s.all.merge(fg, now - start, Long::sum);
        // The TV's totals can drop time around a restart; the events are complete.
        for (Map.Entry<String, Long> w : s.week.entrySet()) s.all.merge(w.getKey(), w.getValue(), Math::max);
        return s;
    }

    private void add(String pkg, long start, long end) {
        for (int i = 0; i < 7; i++) {
            long overlap = Math.min(end, dayStart[i + 1]) - Math.max(start, dayStart[i]);
            if (overlap <= 0) continue;
            week.merge(pkg, overlap, Long::sum);
            if (i == 6) today.merge(pkg, overlap, Long::sum);
            if (!isSystem(pkg)) perDay[i] += overlap;
        }
    }

    boolean isSystem(String pkg) {
        return system.contains(pkg);
    }

    long appTotal(Map<String, Long> times) {
        long total = 0;
        for (Map.Entry<String, Long> t : times.entrySet()) if (!isSystem(t.getKey())) total += t.getValue();
        return total;
    }

    /** Apps with at least a minute, apps first by all-time, then system entries. */
    List<String> ordered() {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>(all.keySet());
        seen.addAll(week.keySet());
        for (String p : seen) if (Math.max(all.getOrDefault(p, 0L), week.getOrDefault(p, 0L)) >= 60_000) out.add(p);
        out.sort((a, b) -> isSystem(a) != isSystem(b) ? (isSystem(a) ? 1 : -1)
                : Long.compare(all.getOrDefault(b, 0L), all.getOrDefault(a, 0L)));
        return out;
    }

    /** Top apps today, most watched first. */
    List<String> topToday(int n) {
        List<String> out = new ArrayList<>();
        for (String p : today.keySet()) if (!isSystem(p) && today.get(p) >= 60_000) out.add(p);
        out.sort((a, b) -> Long.compare(today.get(b), today.get(a)));
        return out.subList(0, Math.min(n, out.size()));
    }

    static String label(Context context, String pkg) {
        PackageManager pm = context.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static String fmt(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) return seconds == 0 ? "-" : seconds + "s";
        long h = seconds / 3600, m = seconds / 60 % 60;
        return h > 0 ? String.format("%dh %02dm", h, m) : m + "m";
    }
}

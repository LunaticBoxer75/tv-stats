package dev.tvstats;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Saves each day's per-app time in the app's own storage, because the TV deletes per-day detail
 * after about 10 days. File: days.json, {"2026-10-14": {"com.netflix.ninja": 4860000, ...}, ...}.
 */
final class History {
    private History() {}

    private static File file(Context context) {
        return new File(context.getFilesDir(), "days.json");
    }

    private static synchronized JSONObject read(Context context) {
        try {
            File f = file(context);
            return f.exists() ? new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)) : new JSONObject();
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    /**
     * Merges the days the TV still has into the saved file and returns the saved totals per app,
     * today included. A day already saved never goes down: the oldest day the TV still has may
     * be partly deleted already.
     */
    static synchronized Map<String, Long> save(Context context, Map<LocalDate, Map<String, Long>> byDay) {
        JSONObject saved = read(context);
        boolean changed = false;
        try {
            for (Map.Entry<LocalDate, Map<String, Long>> day : byDay.entrySet()) {
                String key = day.getKey().toString();
                JSONObject apps = saved.optJSONObject(key);
                if (apps == null) apps = new JSONObject();
                for (Map.Entry<String, Long> app : day.getValue().entrySet()) {
                    if (app.getValue() > apps.optLong(app.getKey(), 0)) {
                        apps.put(app.getKey(), app.getValue());
                        changed = true;
                    }
                }
                saved.put(key, apps);
            }
            if (changed) write(context, saved);
        } catch (JSONException | IOException e) {
            // Keep counting from the TV's own data; the next save retries.
        }
        Map<String, Long> totals = new HashMap<>();
        for (Iterator<String> days = saved.keys(); days.hasNext(); ) {
            JSONObject apps = saved.optJSONObject(days.next());
            for (Iterator<String> pkgs = apps.keys(); pkgs.hasNext(); ) {
                String pkg = pkgs.next();
                totals.merge(pkg, apps.optLong(pkg), Long::sum);
            }
        }
        return totals;
    }

    /** The earliest saved day, or null before the first save. */
    static LocalDate firstDay(Context context) {
        LocalDate first = null;
        for (Iterator<String> days = read(context).keys(); days.hasNext(); ) {
            LocalDate d = LocalDate.parse(days.next());
            if (first == null || d.isBefore(first)) first = d;
        }
        return first;
    }

    private static void write(Context context, JSONObject saved) throws IOException {
        File tmp = new File(context.getFilesDir(), "days.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(saved.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(file(context))) throw new IOException("rename failed");
    }
}

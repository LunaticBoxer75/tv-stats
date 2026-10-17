package dev.tvstats;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** When the TV boots and after the app is updated: schedules saving, and starts the Home panel if turned on. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            SaveJob.schedule(context);
            HomePanelService.startIfEnabled(context);
        }
    }
}

package dev.tvstats;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Saves the days the TV still has every 6 hours, long before it deletes them after about 10 days. */
public final class SaveJob extends JobService {
    static void schedule(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs.getPendingJob(1) != null) return;
        jobs.schedule(new JobInfo.Builder(1, new ComponentName(context, SaveJob.class))
                .setPeriodic(6 * 3600_000L).setPersisted(true).build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            Stats.load(this);   // loading saves
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}

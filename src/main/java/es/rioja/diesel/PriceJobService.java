package es.rioja.diesel;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Comprobación periódica en segundo plano (cada 15 min, el mínimo que permite Android). */
public class PriceJobService extends JobService {

    private static final int JOB_ID = 1;
    private static final long INTERVALO_MS = 15 * 60 * 1000L;

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            PriceChecker.Result r = PriceChecker.check(ctx);
            if (r.error == null && !r.drops.isEmpty()) {
                Notifier.notifyDrops(ctx, r.drops);
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, PriceJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(INTERVALO_MS)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }
}

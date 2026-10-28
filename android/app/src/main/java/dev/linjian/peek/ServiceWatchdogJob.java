package dev.linjian.peek;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** System-owned watchdog. Unlike the old in-process timer, it can run after the app was killed. */
public class ServiceWatchdogJob extends JobService {
    private static final int PERIODIC_JOB_ID = 20260716;
    private static final int RECOVERY_JOB_ID = 20260717;
    private static final long FIFTEEN_MINUTES = 15L * 60L * 1000L;

    public static void schedulePeriodic(Context context) {
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) return;
            JobInfo job = new JobInfo.Builder(PERIODIC_JOB_ID, new ComponentName(context, ServiceWatchdogJob.class))
                    .setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(FIFTEEN_MINUTES)
                    .build();
            scheduler.schedule(job);
        } catch (Exception e) {
            DebugState.append(context, "安排周期看门狗失败：" + ScreenshotService.shortMsg(e));
        }
    }

    public static void scheduleSoon(Context context, long delayMs) {
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) return;
            JobInfo job = new JobInfo.Builder(RECOVERY_JOB_ID, new ComponentName(context, ServiceWatchdogJob.class))
                    .setMinimumLatency(Math.max(1_000L, delayMs))
                    .setOverrideDeadline(Math.max(10_000L, delayMs + 10_000L))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build();
            scheduler.schedule(job);
        } catch (Exception e) {
            DebugState.append(context, "安排快速恢复失败：" + ScreenshotService.shortMsg(e));
        }
    }

    public static void ensureRunning(Context context, String source) {
        if (context == null || AppPrefs.userStopped(context) || !AppPrefs.hasConnectionConfig(context)) return;
        try {
            Intent service = new Intent(context, CompanionService.class);
            service.putExtra("server_url", AppPrefs.server(context));
            service.putExtra("token", AppPrefs.token(context));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
            else context.startService(service);
            DebugState.append(context, "看门狗恢复服务：" + source);
        } catch (Exception e) {
            DebugState.append(context, "看门狗恢复失败：" + ScreenshotService.shortMsg(e));
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        ensureRunning(this, params != null && params.getJobId() == PERIODIC_JOB_ID ? "periodic" : "recovery");
        jobFinished(params, false);
        return false;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }
}

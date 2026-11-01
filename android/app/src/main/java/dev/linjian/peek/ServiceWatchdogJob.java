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
                    .setPeriodic(FIFTEEN_MINUTES)
                    .build();
            if (!AppPrefs.userStopped(context)) scheduler.schedule(job);
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
                    .build();
            if (!AppPrefs.userStopped(context)) scheduler.schedule(job);
        } catch (Exception e) {
            DebugState.append(context, "安排快速恢复失败：" + ScreenshotService.shortMsg(e));
        }
    }

    public static void ensureRunning(Context context, String source) {
        if (context == null || AppPrefs.userStopped(context) || !AppPrefs.hasConnectionConfig(context)) return;
        if (CompanionService.loopFresh()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        long last = AppPrefs.get(context).getLong("last_recovery_elapsed", 0L);
        if (!"manual".equals(source) && last > 0 && now >= last && now - last < 60000L) return;
        AppPrefs.get(context).edit().putLong("last_recovery_elapsed", now).apply();
        try {
            if (CompanionService.isRunning()) context.stopService(new Intent(context, CompanionService.class));
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
        return false;
    }

    public static void cancel(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) { scheduler.cancel(PERIODIC_JOB_ID); scheduler.cancel(RECOVERY_JOB_ID); }
    }

    @Override public boolean onStopJob(JobParameters params) { return !AppPrefs.userStopped(this); }
}

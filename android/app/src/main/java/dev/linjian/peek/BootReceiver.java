package dev.linjian.peek;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores the bridge after a reboot or app update when the user left it enabled. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || AppPrefs.userStopped(context) || !AppPrefs.hasConnectionConfig(context)) return;
        DebugState.append(context, "系统启动/更新：安排恢复掌心窗服务");
        ServiceWatchdogJob.schedulePeriodic(context);
        ServiceWatchdogJob.scheduleSoon(context, 3_000L);
    }
}

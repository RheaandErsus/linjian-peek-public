package dev.linjian.peek;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import org.json.JSONObject;

final class AccessibilityStatus {
    private AccessibilityStatus() { }

    static String state(Context context) {
        Boolean enabled = null;
        try {
            String providers = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            int global = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            ComponentName own = new ComponentName(context, ScreenshotService.class);
            enabled = false;
            if (global == 1 && providers != null) for (String value : providers.split(":")) {
                if (own.equals(ComponentName.unflattenFromString(value))) { enabled = true; break; }
            }
        } catch (RuntimeException ignored) { }
        return ReliabilityPolicy.accessibilityState(enabled, ScreenshotService.ready());
    }

    static boolean ready(Context context) { return "connected".equals(state(context)); }

    static String explanation(Context context) {
        switch (state(context)) {
            case "disabled": return "无障碍被关闭，请在系统设置手动开启";
            case "disconnected": return "无障碍已开启，但服务未连接";
            case "connected": return "无障碍已连接";
            default: return "无障碍开关状态读取失败";
        }
    }

    static JSONObject snapshot(Context context) {
        JSONObject out = new JSONObject();
        try {
            String state = state(context);
            boolean ready = "connected".equals(state);
            out.put("state", state);
            out.put("enabled", "unknown".equals(state) ? JSONObject.NULL : !"disabled".equals(state));
            out.put("service_connected", ScreenshotService.ready());
            out.put("observed_at_ms", System.currentTimeMillis());
            out.put("gestures_available", ready && Build.VERSION.SDK_INT >= 24);
            out.put("screen_reading_available", ready);
            out.put("screenshot_available", ReliabilityPolicy.screenshotAvailable(ready, Build.VERSION.SDK_INT, ProjectionCaptureService.ready()));
            out.put("projection_authorized", ProjectionCaptureService.ready());
            out.put("projection_status", ProjectionCaptureService.status());
        } catch (Exception ignored) { }
        return out;
    }
}

package dev.linjian.peek;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.SystemClock;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.AlarmClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class CompanionService extends Service {
    private static final String CHANNEL_ID = "linjian_peek_service";
    private static final String REMINDER_CHANNEL_ID = "linjian_peek_heads_up_v3";
    private static final int NOTIFICATION_ID = 20260715;
    private static volatile boolean running = false;

    private String serverUrl;
    private String token;
    private Handler pollHandler;
    private HandlerThread pollThread;
    private int consecutiveFailures = 0;
    private volatile boolean active;
    private static volatile long loopElapsed;
    private long lastUploadElapsed;
    private long lastHeartbeatElapsed;
    private final AtomicBoolean commandBusy = new AtomicBoolean();
    private final ThreadPoolExecutor commands = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>());
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Runnable pollTask = this::pollLoop;
    public static boolean loopFresh() { return running && ReliabilityPolicy.fresh(loopElapsed, SystemClock.elapsedRealtime(), 120000L); }

    private void wakePolling() {
        Handler handler = pollHandler;
        if (handler != null && active) handler.post(() -> {
            handler.removeCallbacks(pollTask);
            if (active) handler.post(pollTask);
        });
    }

    public static boolean isRunning() { return running; }
    private static final class HttpFailure extends java.io.IOException {
        final int status;
        HttpFailure(int status) { super("HTTP " + status); this.status = status; }
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            AppPrefs.get(this).edit().putBoolean(AppPrefs.KEY_USER_STOPPED, true).commit();
            ServiceWatchdogJob.cancel(this);
            stopSelf(); return START_NOT_STICKY;
        }
        if (AppPrefs.userStopped(this) || !AppPrefs.hasConnectionConfig(this)) {
            stopSelf(); return START_NOT_STICKY;
        }
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("远程桥运行中；截图、手势与 Curbox 状态独立检查"));
        serverUrl = ScreenshotService.normalizeUrl(AppPrefs.server(this));
        token = AppPrefs.token(this);
        if (!active) {
            active = true; running = true;
            ServiceWatchdogJob.schedulePeriodic(this);
            CurboxClient.resume(this);
            startPolling();
            connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { wakePolling(); }
            };
            try {
                if (Build.VERSION.SDK_INT >= 24) connectivity.registerDefaultNetworkCallback(networkCallback);
                else connectivity.registerNetworkCallback(new NetworkRequest.Builder().build(), networkCallback);
            } catch (RuntimeException e) { networkCallback = null; }
            DebugState.append(this, "守护版 v0.4.1 远程桥已启动，不要求无障碍开启");
        }
        return START_STICKY;
    }

    private void startPolling() {
        loopElapsed = SystemClock.elapsedRealtime();
        AppPrefs.get(this).edit().putLong(AppPrefs.KEY_SERVICE_HEARTBEAT_MS, System.currentTimeMillis()).apply();
        pollThread = new HandlerThread("LinjianUnifiedPoll");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        DebugState.append(this, "前台轮询线程已启动");
        pollHandler.post(pollTask);
    }

    private void pollLoop() {
        if (!active || AppPrefs.userStopped(this)) return;
        loopElapsed = SystemClock.elapsedRealtime();
        CurboxClient.ensureBound(this);
        CurboxClient.refreshAsync(this);
        boolean success = false;
        try {
            if (connectivity != null && (connectivity.getActiveNetworkInfo() == null || !connectivity.getActiveNetworkInfo().isConnected())) {
                throw new java.io.IOException("network_unavailable");
            }
            if (!commandBusy.get()) {
                String body = pollServer();
                AppPrefs.get(this).edit().putLong("last_poll_success_ms", System.currentTimeMillis()).apply();
                if (body != null && !body.isEmpty() && commandBusy.compareAndSet(false, true)) {
                    try {
                        commands.execute(() -> {
                            try { if (active && !AppPrefs.userStopped(this)) handleCommandBody(this, body, serverUrl, token); }
                            finally { commandBusy.set(false); }
                        });
                    } catch (java.util.concurrent.RejectedExecutionException busy) { commandBusy.set(false); }
                }
            }
            if (lastUploadElapsed == 0 || SystemClock.elapsedRealtime() - lastUploadElapsed >= 30000L) {
                uploadState(serverUrl, token, this);
                lastUploadElapsed = SystemClock.elapsedRealtime();
            }
            success = true; consecutiveFailures = 0;
            AppPrefs.get(this).edit().putString(AppPrefs.KEY_LAST_SYNC_ERROR, "").apply();
        } catch (Exception e) {
            consecutiveFailures = Math.min(10, consecutiveFailures + 1);
            String message;
            if (e instanceof HttpFailure) {
                int status = ((HttpFailure) e).status;
                message = status == 401 || status == 403 ? "鉴权失败：HTTP " + status + "，请核对已保存的 Token" : "后台请求失败：HTTP " + status;
            } else message = e instanceof java.io.IOException ? "网络不可用或请求超时" : "同步失败：" + e.getClass().getSimpleName();
            AppPrefs.get(this).edit().putString(AppPrefs.KEY_LAST_SYNC_ERROR, message).apply();
            if (consecutiveFailures == 1 || consecutiveFailures % 5 == 0) DebugState.append(this, message + "，正在退避重连");
        }
        if (SystemClock.elapsedRealtime() - lastHeartbeatElapsed >= 30000L) {
            lastHeartbeatElapsed = SystemClock.elapsedRealtime();
            AppPrefs.get(this).edit().putLong(AppPrefs.KEY_SERVICE_HEARTBEAT_MS, System.currentTimeMillis())
                    .apply();
        }
        long base = Math.max(1500L, AppPrefs.interval(this));
        long delay = success ? base : ReliabilityPolicy.retryDelay(base, consecutiveFailures, Math.random());
        if (active) { pollHandler.removeCallbacks(pollTask); pollHandler.postDelayed(pollTask, delay); }
    }

    private String pollServer() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(serverUrl + "/api/poll?device_id=" + java.net.URLEncoder.encode(AppPrefs.device(this), "UTF-8")).openConnection();
        conn.setConnectTimeout(10000); conn.setReadTimeout(15000); conn.setRequestMethod("GET"); conn.setRequestProperty("X-Auth-Token", token);
        try {
            int code = conn.getResponseCode(); String body = ScreenshotService.readBody(conn, code);
            if (code == 200) {
                JSONObject envelope = new JSONObject(body);
                if (!envelope.has("command")) throw new java.io.IOException("invalid_poll_response");
                if (envelope.isNull("command")) return "";
                DebugState.append(this, "轮询成功：收到命令包"); return body;
            }
            throw new HttpFailure(code);
        } finally { conn.disconnect(); }
    }

    public static void handleCommandBody(Context ctx, String body, String serverUrl, String token) {
        try {
            JSONObject obj = new JSONObject(body);
            Object raw = obj.opt("command");
            if (raw == null || raw == JSONObject.NULL) return;
            if (raw instanceof String) {
                String s = (String) raw;
                if ("peek".equals(s)) executeCommand(ctx, "", "peek", "", "", 0,0,0,0,0,0,350,0,0,"掌心窗", "掌心窗截图", true, serverUrl, token);
                return;
            }
            JSONObject cmd = (JSONObject) raw;
            String id = cmd.optString("id", "");
            String action = cmd.optString("action", "noop");
            if (AppPrefs.userStopped(ctx)) return;
            if (!ReliabilityPolicy.readOnlyCommand(action) && !claimCommand(ctx, id)) {
                try { reportCommand(ctx, serverUrl, token, id, false, "duplicate_or_unconfirmed_command_not_replayed"); } catch (Exception ignored) { }
                return;
            }
            String app = cmd.optString("app", "");
            String pkg = cmd.optString("package", "");
            float x = (float) cmd.optDouble("x", 0);
            float y = (float) cmd.optDouble("y", 0);
            float x1 = (float) cmd.optDouble("x1", 0);
            float y1 = (float) cmd.optDouble("y1", 0);
            float x2 = (float) cmd.optDouble("x2", 0);
            float y2 = (float) cmd.optDouble("y2", 0);
            long duration = cmd.optLong("duration", 350);
            int hour = cmd.optInt("hour", -1);
            int minute = cmd.optInt("minute", -1);
            String title = cmd.optString("title", "掌心窗提醒");
            String message = cmd.optString("message", "掌心窗闹钟");
            boolean vibrate = cmd.optBoolean("vibrate", true);
            boolean skipUi = cmd.optBoolean("skip_ui", true);
            String targetText = cmd.optString("target_text", cmd.optString("query", ""));
            String inputText = cmd.optString("text", cmd.optString("input_text", ""));
            String match = cmd.optString("match", "contains");
            int index = cmd.optInt("index", 1);
            boolean append = cmd.optBoolean("append", false);
            if ("save_known_app".equals(action)) {
                String alias = cmd.optString("alias", app);
                String p = cmd.optString("package", pkg);
                AppPrefs.saveCustomApp(ctx, alias, p);
                String result = "saved_known_app:" + alias + "=" + p;
                DebugState.append(ctx, result);
                try { reportCommand(ctx, serverUrl, token, id, true, result); uploadState(serverUrl, token, ctx); } catch (Exception ignored) { }
                return;
            }
            if (isAppGateAction(action)) {
                JSONObject rr = AppGate.handleCommand(ctx, cmd);
                boolean ok = rr.optBoolean("ok", false);
                String result = rr.optString("result", rr.toString());
                DebugState.append(ctx, "执行应用门禁命令 " + action + "：" + result);
                try { reportCommand(ctx, serverUrl, token, id, ok, result); uploadState(serverUrl, token, ctx); } catch (Exception ignored) { }
                return;
            }
            if (CurboxClient.isCurboxAction(action)) {
                JSONObject rr = CurboxClient.handleCommand(ctx, cmd);
                boolean ok = rr.optBoolean("ok", false);
                String result = rr.opt("result") == null ? rr.toString() : String.valueOf(rr.opt("result"));
                DebugState.append(ctx, "执行 Curbox 命令 " + action + "：" + result);
                try { reportCommand(ctx, serverUrl, token, id, ok, rr.toString()); uploadState(serverUrl, token, ctx); } catch (Exception ignored) { }
                return;
            }
            if ("run_sequence".equals(action)) {
                executeSequence(ctx, id, cmd, serverUrl, token);
                return;
            }
            executeCommand(ctx, id, action, app, pkg, x, y, x1, y1, x2, y2, duration, hour, minute, title, message, vibrate, serverUrl, token, skipUi, targetText, inputText, match, index, append);
        } catch (Exception e) { DebugState.append(ctx, "命令解析异常：" + ScreenshotService.shortMsg(e)); }
    }

    private static synchronized boolean claimCommand(Context context, String id) {
        if (id.isEmpty()) return false;
        try {
            long now = System.currentTimeMillis();
            JSONObject old = new JSONObject(AppPrefs.get(context).getString("command_receipts", "{}"));
            if (old.has(id)) return false;
            JSONObject recent = new JSONObject();
            java.util.Iterator<String> keys = old.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (now - old.optLong(key) < 7L * 24L * 60L * 60L * 1000L) recent.put(key, old.optLong(key));
            }
            // Refuse new work rather than evict a live receipt and risk replaying a write.
            if (recent.length() >= 5000) return false;
            recent.put(id, now);
            return AppPrefs.get(context).edit().putString("command_receipts", recent.toString()).commit();
        } catch (Exception e) { return false; }
    }

    private static boolean isAppGateAction(String action) {
        return "lock_app".equals(action) || "unlock_app".equals(action) || "temporary_unlock_app".equals(action) || "extend_lock".equals(action) || "deny_unlock_request".equals(action) || "get_lock_state".equals(action) || "set_emergency_passphrase".equals(action) || "add_locked_app".equals(action) || "remove_locked_app".equals(action) || "list_lockable_apps".equals(action);
    }

    private static void executeCommand(Context ctx, String id, String action, String app, String pkg, float x, float y, float x1, float y1, float x2, float y2, long duration, int hour, int minute, String title, String message, boolean vibrate, String serverUrl, String token) {
        executeCommand(ctx, id, action, app, pkg, x, y, x1, y1, x2, y2, duration, hour, minute, title, message, vibrate, serverUrl, token, true, "", "", "contains", 1, false);
    }

    private static void executeCommand(Context ctx, String id, String action, String app, String pkg, float x, float y, float x1, float y1, float x2, float y2, long duration, int hour, int minute, String title, String message, boolean vibrate, String serverUrl, String token, boolean skipUi) {
        executeCommand(ctx, id, action, app, pkg, x, y, x1, y1, x2, y2, duration, hour, minute, title, message, vibrate, serverUrl, token, skipUi, "", "", "contains", 1, false);
    }

    private static void executeCommand(Context ctx, String id, String action, String app, String pkg, float x, float y, float x1, float y1, float x2, float y2, long duration, int hour, int minute, String title, String message, boolean vibrate, String serverUrl, String token, boolean skipUi, String targetText, String inputText, String match, int index, boolean append) {
        JSONObject one = performAction(ctx, action, app, pkg, x, y, x1, y1, x2, y2, duration, hour, minute, title, message, vibrate, serverUrl, token, skipUi, targetText, inputText, match, index, append);
        boolean ok = one.optBoolean("ok", false);
        String result = one.optString("result", one.toString());
        DebugState.append(ctx, "执行命令 " + action + "：" + result);
        try { reportCommand(ctx, serverUrl, token, id, ok, result); uploadState(serverUrl, token, ctx); } catch (Exception ignored) { }
    }

    private static JSONObject performAction(Context ctx, String action, String app, String pkg, float x, float y, float x1, float y1, float x2, float y2, long duration, int hour, int minute, String title, String message, boolean vibrate, String serverUrl, String token, boolean skipUi, String targetText, String inputText, String match, int index, boolean append) {
        JSONObject out = new JSONObject();
        boolean ok = false; String result = "";
        try {
            ScreenshotService svc = AccessibilityStatus.ready(ctx) ? ScreenshotService.getInstance() : null;
            if ("wait".equals(action)) { ok = true; result = "wait";
            } else if ("get_life_state".equals(action)) { ok = true; result = LifeState.collect(ctx).toString();
            } else if (isAppGateAction(action)) { JSONObject rr = AppGate.handleCommand(ctx, new JSONObject().put("action", action).put("app", app).put("package", pkg)); ok = rr.optBoolean("ok", false); result = rr.optString("result", rr.toString());
            } else if ("get_screen_nodes".equals(action)) {
                if (svc != null) { svc.refreshScreenModel(); ok = true; result = svc.getScreenNodesJsonNow(); }
                else result = AccessibilityStatus.explanation(ctx);
            } else if ("tap_text".equals(action)) {
                if (svc != null) { JSONObject rr = svc.tapText(targetText, match, index); ok = rr.optBoolean("ok", false); result = rr.toString(); }
                else result = AccessibilityStatus.explanation(ctx);
            } else if ("input_text".equals(action)) {
                if (svc != null) { JSONObject rr = svc.inputText(inputText, append); ok = rr.optBoolean("ok", false); result = rr.toString(); }
                else result = AccessibilityStatus.explanation(ctx);
            } else if ("peek".equals(action)) {
                if (svc != null && Build.VERSION.SDK_INT >= 30) { svc.doScreenshot(serverUrl, token); ok = true; result = "accessibility_screenshot_requested"; }
                else if (ProjectionCaptureService.ready()) { ok = ProjectionCaptureService.capture(serverUrl, token); result = ok ? "projection_screenshot_requested" : "projection_capture_busy"; }
                else result = "录屏授权未建立或已失效，请在手机重新授权；" + AccessibilityStatus.explanation(ctx);
            } else if ("open_app".equals(action)) {
                if (pkg == null || pkg.length() == 0) pkg = AppPrefs.packageForApp(ctx, app);
                result = openPackageResult(ctx, pkg);
                ok = result.startsWith("opened_");
            } else if ("home".equals(action)) { ok = svc != null && svc.doHome(); result = ok ? "home" : (svc == null ? AccessibilityStatus.explanation(ctx) : "home_failed");
            } else if ("back".equals(action)) { ok = svc != null && svc.doBack(); result = ok ? "back" : (svc == null ? AccessibilityStatus.explanation(ctx) : "back_failed");
            } else if ("recents".equals(action)) { ok = svc != null && svc.doRecents(); result = ok ? "recents" : (svc == null ? AccessibilityStatus.explanation(ctx) : "recents_failed");
            } else if ("tap".equals(action)) { ok = svc != null && svc.doTap(x, y); result = ok ? ("tap:" + x + "," + y) : (svc == null ? AccessibilityStatus.explanation(ctx) : "tap_failed");
            } else if ("swipe".equals(action)) { ok = svc != null && svc.doSwipe(x1, y1, x2, y2, duration); result = ok ? "swipe" : (svc == null ? AccessibilityStatus.explanation(ctx) : "swipe_failed");
            } else if ("set_alarm".equals(action)) { ok = setAlarm(ctx, hour, minute, message, vibrate, skipUi); result = ok ? "alarm " + hour + ":" + minute : "cannot set alarm";
            } else if ("send_notification".equals(action)) { ok = showReminderNotification(ctx, title, message); result = ok ? "heads_up_notification_sent" : "notification permission missing";
            } else { ok = true; result = "noop"; }
        } catch (Exception e) { result = ScreenshotService.shortMsg(e); }
        try { out.put("ok", ok); out.put("action", action); out.put("result", result); } catch (Exception ignored) { }
        return out;
    }

    private static void executeSequence(Context ctx, String id, JSONObject cmd, String serverUrl, String token) {
        JSONArray report = new JSONArray();
        boolean allOk = true;
        int executed = 0;
        boolean stopOnError = cmd.optBoolean("stop_on_error", true);
        JSONArray steps = cmd.optJSONArray("steps");
        if (steps == null) {
            JSONObject payload = cmd.optJSONObject("payload");
            if (payload != null) steps = payload.optJSONArray("steps");
        }
        if (steps == null) steps = new JSONArray();
        int count = Math.min(12, steps.length());
        DebugState.append(ctx, "开始执行动作序列：" + count + " 步，stop_on_error=" + stopOnError);
        for (int i = 0; i < count; i++) {
            JSONObject stepReport = new JSONObject();
            try {
                JSONObject step = steps.optJSONObject(i);
                if (step == null) step = new JSONObject();
                String action = step.optString("action", "noop");
                String label = step.optString("label", action);
                String app = step.optString("app", "");
                String pkg = step.optString("package", step.optString("pkg", ""));
                float x = (float) step.optDouble("x", 0); float y = (float) step.optDouble("y", 0);
                float x1 = (float) step.optDouble("x1", 0); float y1 = (float) step.optDouble("y1", 0);
                float x2 = (float) step.optDouble("x2", 0); float y2 = (float) step.optDouble("y2", 0);
                long duration = step.optLong("duration", 350);
                int hour = step.optInt("hour", -1); int minute = step.optInt("minute", -1);
                String title = step.optString("title", "掌心窗提醒");
                String message = step.optString("message", "宝宝，看一眼这里。");
                boolean vibrate = step.optBoolean("vibrate", true);
                boolean skipUi = step.optBoolean("skip_ui", true);
                String targetText = step.optString("target_text", step.optString("query", ""));
                String inputText = step.optString("text", step.optString("input_text", ""));
                String match = step.optString("match", "contains");
                int textIndex = step.optInt("index", 1);
                boolean append = step.optBoolean("append", false);
                JSONObject r = performAction(ctx, action, app, pkg, x, y, x1, y1, x2, y2, duration, hour, minute, title, message, vibrate, serverUrl, token, skipUi, targetText, inputText, match, textIndex, append);
                int wait = clampWait(step.optInt("wait_ms", step.optInt("delay_ms", 0)));
                if (wait > 0) Thread.sleep(wait);
                String expect = step.optString("expect_app", "").trim();
                if (expect.length() > 0) {
                    String expectedPkg = AppPrefs.packageForApp(ctx, expect);
                    if (expectedPkg.length() == 0 && AppPrefs.isPackageLike(expect)) expectedPkg = expect;
                    String current = ScreenshotService.currentPackage();
                    boolean expectMatch = current != null && current.equals(expectedPkg);
                    r.put("expect_app", expect);
                    r.put("expected_package", expectedPkg);
                    r.put("current_package", current == null ? "" : current);
                    r.put("expect_ok", expectMatch);
                    if (!expectMatch) r.put("ok", false);
                }
                boolean ok = r.optBoolean("ok", false);
                stepReport.put("index", i + 1); stepReport.put("label", label); stepReport.put("action", action); stepReport.put("ok", ok); stepReport.put("detail", r);
                report.put(stepReport);
                executed++;
                DebugState.append(ctx, "序列步骤 " + (i + 1) + "/" + count + " [" + label + "]：" + r.optString("result", ""));
                if (!ok) { allOk = false; if (stopOnError) break; }
            } catch (Exception e) {
                allOk = false;
                try { stepReport.put("index", i + 1); stepReport.put("ok", false); stepReport.put("error", ScreenshotService.shortMsg(e)); report.put(stepReport); } catch (Exception ignored) { }
                DebugState.append(ctx, "序列步骤 " + (i + 1) + " 异常：" + ScreenshotService.shortMsg(e));
                if (stopOnError) break;
            }
        }
        JSONObject finalReport = new JSONObject();
        try {
            finalReport.put("ok", allOk);
            finalReport.put("executed", executed);
            finalReport.put("total", count);
            finalReport.put("current_package", ScreenshotService.currentPackage());
            finalReport.put("steps", report);
        } catch (Exception ignored) { }
        DebugState.append(ctx, "动作序列结束：" + (allOk ? "全部成功" : "有步骤失败") + "，执行 " + executed + "/" + count);
        try { reportCommand(ctx, serverUrl, token, id, allOk, finalReport.toString()); uploadState(serverUrl, token, ctx); } catch (Exception ignored) { }
    }

    private static int clampWait(int v) { return Math.max(0, Math.min(5000, v)); }

    public static String openPackageResult(Context ctx, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return "package_empty";
        String target = pkg.trim();
        try {
            PackageManager pm = ctx.getPackageManager();
            try { pm.getPackageInfo(target, 0); } catch (Exception missing) { return "package_not_found:" + target; }

            Intent standard = pm.getLaunchIntentForPackage(target);
            if (standard != null) {
                standard.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                try {
                    ctx.startActivity(standard);
                    return "opened_standard:" + target;
                } catch (Exception e) {
                    DebugState.append(ctx, "标准启动失败 " + target + "：" + ScreenshotService.shortMsg(e));
                }
            }

            Intent query = new Intent(Intent.ACTION_MAIN);
            query.addCategory(Intent.CATEGORY_LAUNCHER);
            query.setPackage(target);
            List<ResolveInfo> launchers = pm.queryIntentActivities(query, 0);
            if (launchers == null || launchers.isEmpty()) return "no_launch_intent:" + target;

            ResolveInfo best = launchers.get(0);
            if (best == null || best.activityInfo == null) return "no_launch_activity:" + target;

            Intent explicit = new Intent(Intent.ACTION_MAIN);
            explicit.addCategory(Intent.CATEGORY_LAUNCHER);
            explicit.setClassName(best.activityInfo.packageName, best.activityInfo.name);
            explicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(explicit);
            return "opened_launcher_activity:" + best.activityInfo.packageName + "/" + best.activityInfo.name;
        } catch (Exception e) {
            return "activity_start_failed:" + target + ":" + ScreenshotService.shortMsg(e);
        }
    }

    public static boolean showReminderNotification(Context ctx, String title, String message) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            String safeTitle = (title == null || title.trim().isEmpty()) ? "掌心窗提醒" : title.trim();
            String safeMessage = (message == null || message.trim().isEmpty()) ? "宝宝，看一眼这里。" : message.trim();

            Intent detail = new Intent(ctx, ReminderActivity.class);
            detail.putExtra("title", safeTitle);
            detail.putExtra("message", safeMessage);
            detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, (int)(System.currentTimeMillis() % 100000), detail, Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(REMINDER_CHANNEL_ID, "掌心窗悬浮横幅提醒", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("像微信消息一样从顶部弹出的横幅提醒；点开后可进入详情页。");
                channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                channel.enableVibration(true);
                nm.createNotificationChannel(channel);
            }
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(ctx, REMINDER_CHANNEL_ID) : new Notification.Builder(ctx);
            Notification n = builder
                    .setContentTitle(safeTitle)
                    .setContentText(safeMessage)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .build();
            nm.notify((int)(System.currentTimeMillis() % Integer.MAX_VALUE), n);
            DebugState.append(ctx, "悬浮横幅通知已发送：" + safeTitle);
            return true;
        } catch (Exception e) { DebugState.append(ctx, "悬浮横幅通知异常：" + ScreenshotService.shortMsg(e)); return false; }
    }

    private static boolean setAlarm(Context ctx, int hour, int minute, String message, boolean vibrate, boolean skipUi) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return false;
        try {
            Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
            i.putExtra(AlarmClock.EXTRA_HOUR, hour);
            i.putExtra(AlarmClock.EXTRA_MINUTES, minute);
            i.putExtra(AlarmClock.EXTRA_MESSAGE, message == null || message.length() == 0 ? "掌心窗闹钟" : message);
            i.putExtra(AlarmClock.EXTRA_VIBRATE, vibrate);
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(ctx.getPackageManager()) == null) return false;
            ctx.startActivity(i);
            return true;
        } catch (Exception e) { return false; }
    }

    private static void uploadState(String serverUrl, String token, Context ctx) throws Exception {
        JSONObject state = LifeState.collect(ctx);
        postJson(serverUrl + "/api/device/state", token, state);
        AppPrefs.get(ctx).edit().putLong(AppPrefs.KEY_LAST_SYNC_MS, System.currentTimeMillis()).apply();
        ActiveReminder.evaluate(ctx, state);
        HomeMode.evaluate(ctx, state);
    }

    private static void reportCommand(Context ctx, String serverUrl, String token, String id, boolean ok, String result) throws Exception {
        if (id == null || id.length() == 0) return;
        JSONObject report = new JSONObject(); report.put("device_id", AppPrefs.device(ctx)); report.put("command_id", id); report.put("ok", ok); report.put("result", result);
        postJson(serverUrl + "/api/device/report", token, report);
    }

    private static String postJson(String urlStr, String token, JSONObject obj) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(10000); conn.setReadTimeout(15000); conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-Auth-Token", token); conn.setDoOutput(true);
            byte[] data = obj.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) { os.write(data); }
            int code = conn.getResponseCode();
            String body = ScreenshotService.readBody(conn, code);
            if (code < 200 || code >= 300) throw new HttpFailure(code);
            return body;
        } finally { conn.disconnect(); }
    }

    private void createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { NotificationManager nm = getSystemService(NotificationManager.class); NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "掌心窗", NotificationManager.IMPORTANCE_LOW); channel.setDescription("掌心窗正在等待你授权的截图与手机动作请求"); nm.createNotificationChannel(channel); NotificationChannel reminder = new NotificationChannel(REMINDER_CHANNEL_ID, "掌心窗悬浮横幅提醒", NotificationManager.IMPORTANCE_HIGH); reminder.setDescription("来自掌心窗的悬浮横幅、生活提醒与回家模式"); reminder.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC); reminder.enableVibration(true); nm.createNotificationChannel(reminder); } }
    private Notification buildNotification(String text) { Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this); return builder.setContentTitle("掌心窗运行中").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build(); }
    @Override public void onTaskRemoved(Intent rootIntent) {
        if (!AppPrefs.userStopped(this)) ServiceWatchdogJob.scheduleSoon(this, 3_000L);
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        active = false; running = false;
        commands.shutdownNow();
        if (connectivity != null && networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) { }
        }
        CurboxClient.stop();
        DebugState.append(this, "服务已销毁/停止");
        if (pollHandler != null) pollHandler.removeCallbacksAndMessages(null);
        if (pollThread != null) pollThread.quitSafely();
        if (!AppPrefs.userStopped(this)) ServiceWatchdogJob.scheduleSoon(this, 5_000L);
        super.onDestroy();
    }
}

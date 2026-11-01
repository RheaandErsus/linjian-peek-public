package dev.linjian.peek;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.IBinder;

import neth.iecal.curbox.api.ICurboxApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;


/** Thin, allowlisted Binder bridge to Curbox. Curbox remains the local enforcement engine. */
public final class CurboxClient {
    public static final String ACTION_BIND = "neth.iecal.curbox.api.BIND";
    public static final String ACTION_REQUEST_PERMISSION = "neth.iecal.curbox.api.REQUEST_PERMISSION";
    private static final String RELEASE_PACKAGE = "neth.iecal.curbox";

    private static final Object LOCK = new Object();
    private static final BoundedCalls IO = new BoundedCalls();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile long generation, statusAt, statusElapsed, nextBind, bindStarted, lastRefresh;
    private static volatile boolean bound, refreshPending, stopped;
    private static int bindFailures;
    private static volatile ICurboxApi api;
    private static volatile Context appContext;
    private static final Runnable RETRY = () -> ensureBound(appContext);
    private static volatile boolean bindingRequested;
    private static volatile boolean connected;
    private static volatile boolean granted;
    private static volatile int apiVersion;
    private static volatile String selectedPackage = "";
    private static volatile String lastError = "";
    private static volatile String cachedStatus = "";

    private CurboxClient() { }

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (LOCK) {
                if (stopped) { releaseLocked(); return; }
                api = ICurboxApi.Stub.asInterface(service); connected = api != null;
                bindingRequested = false; nextBind = 0; generation++;
                lastError = "";
            }
            refreshAsync(appContext);
        }
        @Override public void onServiceDisconnected(ComponentName name) { connectionLost("Curbox 连接断开"); }
        @Override public void onBindingDied(ComponentName name) { connectionLost("Curbox 绑定失效"); }
        @Override public void onNullBinding(ComponentName name) { connectionLost("Curbox 接口不可用"); }
    };

    public static void resume(Context context) {
        stopped = false; ensureBound(context);
    }

    public static void stop() {
        synchronized (LOCK) { stopped = true; MAIN.removeCallbacks(RETRY); releaseLocked(); }
    }

    private static void releaseLocked() {
        generation++; api = null; connected = false; granted = false;
        cachedStatus = ""; statusAt = 0; statusElapsed = 0;
        if (bound && appContext != null) {
            try { appContext.unbindService(CONNECTION); } catch (RuntimeException ignored) { }
        }
        bound = false; bindingRequested = false;
    }

    private static void connectionLost(String reason) {
        synchronized (LOCK) {
            releaseLocked(); lastError = reason;
            bindFailures = Math.min(10, bindFailures + 1);
            long delay = ReliabilityPolicy.retryDelay(2000L, bindFailures, Math.random());
            nextBind = SystemClock.elapsedRealtime() + delay;
            MAIN.removeCallbacks(RETRY);
            if (!stopped && appContext != null) MAIN.postDelayed(RETRY, delay);
        }
    }

    public static void ensureBound(Context context) {
        if (context == null || stopped) return;
        appContext = context.getApplicationContext();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.removeCallbacks(RETRY); MAIN.post(RETRY); return;
        }
        synchronized (LOCK) {
            if (stopped) return;
            long now = SystemClock.elapsedRealtime();
            if (api != null) return;
            if (bindingRequested) {
                if (now - bindStarted > 15000L) connectionLost("Curbox 连接超时");
                return;
            }
            if (now < nextBind) {
                MAIN.removeCallbacks(RETRY); MAIN.postDelayed(RETRY, nextBind - now); return;
            }
            String pkg = choosePackage(appContext);
            if (pkg.isEmpty()) { connectionLost("未安装 Curbox"); return; }
            selectedPackage = pkg;
            try {
                bindStarted = now;
                bound = appContext.bindService(new Intent(ACTION_BIND).setPackage(pkg), CONNECTION, Context.BIND_AUTO_CREATE);
                bindingRequested = bound;
                if (!bound) connectionLost("Curbox 连接请求被拒绝");
                else { MAIN.removeCallbacks(RETRY); MAIN.postDelayed(RETRY, 16000L); }
            } catch (RuntimeException e) { connectionLost("Curbox 连接失败：" + e.getClass().getSimpleName()); }
        }
    }

    private static String choosePackage(Context context) {
        List<ResolveInfo> services = context.getPackageManager().queryIntentServices(new Intent(ACTION_BIND), 0);
        Set<String> seen = new HashSet<>();
        List<String> packages = new ArrayList<>();
        if (services != null) for (ResolveInfo info : services) {
            if (info != null && info.serviceInfo != null && seen.add(info.serviceInfo.packageName)) packages.add(info.serviceInfo.packageName);
        }
        if (packages.isEmpty()) {
            lastError = "未安装 Curbox";
            selectedPackage = "";
            return "";
        }
        String saved = AppPrefs.get(context).getString(AppPrefs.KEY_CURBOX_PACKAGE, "");
        if (!saved.isEmpty() && packages.contains(saved)) return saved;
        String chosen = packages.contains(RELEASE_PACKAGE) ? RELEASE_PACKAGE : packages.get(0);
        AppPrefs.get(context).edit().putString(AppPrefs.KEY_CURBOX_PACKAGE, chosen).apply();
        if (packages.size() > 1) lastError = "检测到多个 Curbox，已选择 " + chosen;
        return chosen;
    }

    public static String requestPermission(Activity activity, int requestCode) {
        if (activity == null) return "页面不可用";
        ensureBound(activity);
        String pkg = selectedPackage;
        if (pkg.isEmpty()) return lastError.isEmpty() ? "未找到 Curbox" : lastError;
        try {
            activity.startActivityForResult(new Intent(ACTION_REQUEST_PERMISSION).setPackage(pkg), requestCode);
            return "已打开 Curbox 授权页";
        } catch (Exception e) {
            lastError = "打不开 Curbox 授权页：" + ScreenshotService.shortMsg(e);
            return lastError;
        }
    }

    public static void refreshAsync(Context context) {
        if (context != null) appContext = context.getApplicationContext();
        ensureBound(context);
        final ICurboxApi remote = api;
        final long epoch = generation;
        synchronized (LOCK) {
            if (stopped || remote == null || refreshPending || SystemClock.elapsedRealtime() - lastRefresh < 5000L) return;
            refreshPending = true;
            boolean accepted = IO.offer(() -> {
                try {
                    int version = remote.apiVersion();
                    boolean permission = remote.isGranted();
                    String status = permission ? safe(remote.list("STATUS")) : "";
                    synchronized (LOCK) {
                        if (epoch != generation || stopped) return;
                        apiVersion = version; granted = permission; connected = true;
                        bindFailures = 0;
                        cachedStatus = status; statusAt = System.currentTimeMillis(); statusElapsed = SystemClock.elapsedRealtime();
                        lastError = permission ? "" : "等待你在 Curbox 里授权";
                    }
                } catch (Exception e) { MAIN.post(() -> { if (epoch == generation) connectionLost("读取 Curbox 失败"); }); }
                finally { synchronized (LOCK) { refreshPending = false; lastRefresh = SystemClock.elapsedRealtime(); } }
            });
            if (!accepted) { refreshPending = false; lastRefresh = SystemClock.elapsedRealtime(); }
        }
    }

    public static JSONObject snapshot(Context context) {
        JSONObject out = new JSONObject();
        try {
            out.put("installed", !selectedPackage.isEmpty() || hasCandidate(context));
            out.put("package", selectedPackage);
            out.put("connected", connected && api != null);
            out.put("granted", granted);
            out.put("api_version", apiVersion);
            boolean fresh = ReliabilityPolicy.fresh(statusElapsed, SystemClock.elapsedRealtime(), 60000L);
            out.put("status", !fresh || cachedStatus.isEmpty() ? JSONObject.NULL : new JSONObject(cachedStatus));
            out.put("status_observed_at_ms", statusAt);
            out.put("status_fresh", fresh);
            out.put("remote_relaxation_allowed", context != null && AppPrefs.get(context).getBoolean(AppPrefs.KEY_CURBOX_ALLOW_REMOTE_RELAX, false));
            out.put("error", lastError);
        } catch (Exception ignored) { }
        return out;
    }

    private static boolean hasCandidate(Context context) {
        if (context == null) return false;
        try { return !context.getPackageManager().queryIntentServices(new Intent(ACTION_BIND), 0).isEmpty(); }
        catch (Exception ignored) { return false; }
    }

    public static String pretty(Context context) {
        JSONObject s = snapshot(context);
        if (!s.optBoolean("installed")) return "未安装 Curbox · 先安装后再授权";
        if (!s.optBoolean("connected")) return "Curbox 已找到 · 尚未连接\n" + s.optString("error", "");
        if (!s.optBoolean("granted")) return "Curbox 已连接 · 等待授权\n" + s.optString("error", "");
        JSONObject status = s.optJSONObject("status");
        String focus = status != null && status.optBoolean("focusActive")
                ? "专注中 · " + status.optString("focusGroup", "") + " · 剩余 " + status.optInt("focusRemainingMinutes", 0) + " 分钟"
                : (status == null ? "专注状态未知或已过期，等待读取" : "当前未开启专注");
        return "Curbox API v" + s.optInt("api_version", 0) + " · 已授权\n" + focus
                + "\n远程解除限制：" + (s.optBoolean("remote_relaxation_allowed") ? "允许" : "关闭（更安全）");
    }

    public static boolean isCurboxAction(String action) {
        return action != null && action.startsWith("curbox_");
    }

    public static JSONObject handleCommand(Context context, JSONObject cmd) {
        JSONObject out = new JSONObject();
        String action = cmd == null ? "" : cmd.optString("action", "");
        try {
            ensureBound(context);
            if ("curbox_status".equals(action)) {
                String raw = callList("STATUS");
                out.put("ok", raw != null);
                out.put("result", raw == null ? snapshot(context) : new JSONObject(raw));
                return out;
            }
            if ("curbox_query".equals(action)) {
                String state = cmd.optString("state", "");
                if (!allowedQuery(state)) return fail(out, "不允许的 Curbox 查询");
                String raw = callQuery(state);
                out.put("ok", raw != null); out.put("result", raw == null ? "Curbox 未连接或未授权" : new JSONObject(raw));
                return out;
            }
            if ("curbox_list".equals(action)) {
                String kind = cmd.optString("kind", "");
                if (!allowedList(kind)) return fail(out, "不允许的 Curbox 列表");
                String raw = callList(kind);
                out.put("ok", raw != null);
                out.put("result", raw == null ? "Curbox 未连接或未授权" : ("STATUS".equals(kind) ? new JSONObject(raw) : new JSONArray(raw)));
                return out;
            }

            Bundle args = new Bundle();
            String command;
            boolean enabling = cmd.optBoolean("enable", true);
            if (!enabling && !AppPrefs.get(context).getBoolean(AppPrefs.KEY_CURBOX_ALLOW_REMOTE_RELAX, false)) {
                return fail(out, "手机端未允许远程解除限制");
            }
            if ("curbox_start_focus".equals(action)) {
                command = "START_FOCUS";
                args.putString("target", cmd.optString("target", ""));
                args.putInt("minutes", Math.max(1, Math.min(240, cmd.optInt("minutes", 25))));
            } else if ("curbox_set_app_group".equals(action)) {
                command = "SET_APP_BLOCKER_GROUP"; args.putString("target", cmd.optString("target", "")); args.putBoolean("enable", enabling);
            } else if ("curbox_set_keyword_group".equals(action)) {
                command = "SET_KEYWORD_GROUP"; args.putString("target", cmd.optString("target", "")); args.putBoolean("enable", enabling);
            } else if ("curbox_set_grayscale_group".equals(action)) {
                command = "SET_GRAYSCALE_GROUP"; args.putString("target", cmd.optString("target", "")); args.putBoolean("enable", enabling);
            } else if ("curbox_set_keyword_blocker".equals(action)) {
                command = "SET_KEYWORD_BLOCKER"; args.putBoolean("enable", enabling);
            } else if ("curbox_set_reel_blocker".equals(action)) {
                command = "SET_REEL_BLOCKER"; args.putBoolean("enable", enabling);
            } else if ("curbox_set_reel_counter".equals(action)) {
                command = "SET_REEL_COUNTER"; args.putBoolean("enable", enabling);
            } else if ("curbox_set_dnd".equals(action)) {
                command = "SET_DND"; args.putBoolean("enable", enabling);
            } else {
                return fail(out, "Curbox 命令不在白名单");
            }
            String result = callExecute(command, args);
            boolean ok = "OK".equals(result);
            out.put("ok", ok); out.put("result", result == null ? "Curbox 写命令结果未知或未执行；不得自动重放，请先查询状态" : result); out.put("command", command);
        } catch (Exception e) {
            try { out.put("ok", false); out.put("result", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
        }
        return out;
    }

    private static JSONObject fail(JSONObject out, String message) throws Exception { out.put("ok", false); out.put("result", message); return out; }

    private static boolean allowedQuery(String state) {
        return "FOCUS_ACTIVE".equals(state) || "SCREENTIME_TODAY".equals(state) || "REELS_TODAY".equals(state);
    }

    private static boolean allowedList(String kind) {
        return "STATUS".equals(kind) || "FOCUS_GROUPS".equals(kind) || "APP_BLOCKER_GROUPS".equals(kind)
                || "KEYWORD_GROUPS".equals(kind) || "GRAYSCALE_GROUPS".equals(kind) || "AUTO_DND_GROUPS".equals(kind)
                || "UI_HIDER_SCRIPTS".equals(kind);
    }

    private static String invoke(String kind, String value, Bundle args) {
        ICurboxApi remote = api;
        long epoch = generation;
        if (stopped || remote == null || !granted) return null;
        try {
            String result = IO.call(() -> {
                if (stopped || epoch != generation || ("execute".equals(kind) && AppPrefs.userStopped(appContext))) return null;
                if ("execute".equals(kind)) return remote.execute(value, args);
                if ("query".equals(kind)) return remote.query(value);
                return remote.list(value);
            }, 4000L);
            if (epoch != generation) return null;
            if ("list".equals(kind) && "STATUS".equals(value) && result != null) {
                cachedStatus = result; statusAt = System.currentTimeMillis(); statusElapsed = SystemClock.elapsedRealtime();
            }
            if ("execute".equals(kind)) { cachedStatus = ""; statusAt = 0; statusElapsed = 0; }
            return result;
        } catch (Exception e) {
            lastError = "Curbox 调用未确认（" + e.getClass().getSimpleName() + "），写命令不会自动重放";
            if (!(e instanceof java.util.concurrent.RejectedExecutionException)) {
                MAIN.post(() -> { if (epoch == generation) connectionLost("Curbox 调用失败或超时；先查询真实状态"); });
            }
            return null;
        }
    }

    private static String callExecute(String command, Bundle args) { return invoke("execute", command, args); }
    private static String callQuery(String state) { return invoke("query", state, null); }
    private static String callList(String kind) { return invoke("list", kind, null); }

    private static String safe(String value) { return value == null ? "" : value; }
}

package dev.linjian.peek;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A user-consented capture session. Grants are never persisted or restored after death. */
public final class ProjectionCaptureService extends Service {
    private static final String CHANNEL = "guardian_capture";
    private static volatile ProjectionCaptureService instance;
    private static volatile String lastStatus = "录屏授权未建立或已失效，请重新授权";
    private final AtomicBoolean pending = new AtomicBoolean();
    private volatile boolean uploading;
    private final ThreadPoolExecutor uploads = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>());
    private volatile MediaProjection projection;
    private HandlerThread thread;
    private Handler handler;
    private ImageReader reader;
    private VirtualDisplay display;
    private int width, height, density;
    private String uploadUrl, uploadToken;
    private final Runnable captureTimeout = () -> {
        if (pending.compareAndSet(true, false)) {
            lastStatus = "截图等待画面超时，可重试；若录屏已停止请重新授权";
            DebugState.append(this, lastStatus);
        }
    };

    public static boolean ready() {
        ProjectionCaptureService service = instance;
        return service != null && service.projection != null;
    }
    public static String status() { return lastStatus; }

    public static boolean capture(String server, String token) {
        ProjectionCaptureService service = instance;
        if (service == null) return false;
        synchronized (service) {
            if (service.projection == null || service.pending.get()) return false;
            service.uploadUrl = ScreenshotService.normalizeUrl(server);
            service.uploadToken = token;
            service.pending.set(true);
            service.handler.removeCallbacks(service.captureTimeout);
            service.handler.postDelayed(service.captureTimeout, 5000L);
            return true;
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "STOP".equals(intent.getAction())) {
            stopSelf(); return START_NOT_STICKY;
        }
        if (ready()) return START_NOT_STICKY;
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "掌心窗截图授权", NotificationManager.IMPORTANCE_LOW));
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, ProjectionCaptureService.class).setAction("STOP"), piFlags);
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), piFlags);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
            Notification notification = builder.setContentTitle("掌心窗截图授权有效")
                    .setContentText("按请求截图；完成后可结束授权以节省电量")
                    .setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).setContentIntent(open)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "结束截图授权", stop).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(20261030, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(20261030, notification);
            Intent grant = intent.getParcelableExtra("grant");
            if (grant == null || intent.getIntExtra("result_code", Activity.RESULT_CANCELED) != Activity.RESULT_OK) throw new IllegalStateException("需要重新授予录屏权限");
            thread = new HandlerThread("GuardianProjection"); thread.start(); handler = new Handler(thread.getLooper());
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(Activity.RESULT_OK, grant);
            if (projection == null) throw new IllegalStateException("录屏授权失效，请重新授权");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    lastStatus = "录屏授权已失效，请重新授权";
                    projection = null; pending.set(false);
                    instance = null;
                    stopSelf();
                }
                @Override public void onCapturedContentResize(int newWidth, int newHeight) {
                    if (newWidth > 0 && newHeight > 0 && (width != newWidth || height != newHeight)) resize(newWidth, newHeight);
                }
            }, handler);
            DisplayMetrics metrics = new DisplayMetrics();
            ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
            width = metrics.widthPixels; height = metrics.heightPixels; density = metrics.densityDpi;
            reader = newReader(width, height);
            display = projection.createVirtualDisplay("GuardianCapture", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
            if (display == null) throw new IllegalStateException("无法建立录屏画面");
            instance = this;
            lastStatus = "录屏授权有效，截图不依赖无障碍";
            if (intent.getBooleanExtra("capture_now", false)) capture(AppPrefs.server(this), AppPrefs.token(this));
        } catch (Exception e) {
            lastStatus = "录屏授权不可用，请重新授权（" + e.getClass().getSimpleName() + "）";
            DebugState.append(this, lastStatus);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private ImageReader newReader(int w, int h) {
        ImageReader next = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        next.setOnImageAvailableListener(this::onImage, handler);
        return next;
    }

    private void resize(int w, int h) {
        if (display == null || projection == null) return;
        try {
            ImageReader old = reader;
            ImageReader next = newReader(w, h);
            display.resize(w, h, density); display.setSurface(next.getSurface());
            reader = next; width = w; height = h;
            if (old != null) old.close();
        } catch (Exception e) { lastStatus = "录屏画面变化，需重新授权"; instance = null; stopSelf(); }
    }

    private void onImage(ImageReader source) {
        if (source != reader) return;
        Bitmap bitmap = null;
        try (Image image = source.acquireLatestImage()) {
            if (image == null || !pending.get() || uploading) return;
            handler.removeCallbacks(captureTimeout);
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer().duplicate();
            int w = image.getWidth(), h = image.getHeight(), pixel = plane.getPixelStride();
            ByteBuffer compact = RgbaFrame.compact(buffer, w, h, pixel, plane.getRowStride());
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(compact);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            byte[] jpeg = out.toByteArray();
            String server = uploadUrl, token = uploadToken;
            uploading = true;
            uploads.execute(() -> {
                try { ScreenshotService.uploadScreenshot(this, jpeg, server, token); }
                finally {
                    uploading = false; pending.set(false);
                    if (instance == this) lastStatus = projection == null ? "录屏授权已失效，请重新授权" : "录屏授权有效";
                }
            });
        } catch (Exception e) {
            uploading = false; pending.set(false);
            DebugState.append(this, "录屏截图失败（" + e.getClass().getSimpleName() + "），可重试或重新授权");
        } finally { if (bitmap != null) bitmap.recycle(); }
    }

    @Override public void onDestroy() {
        instance = null; pending.set(false);
        MediaProjection current = projection; projection = null;
        if (current != null) current.stop();
        if (display != null) { display.release(); display = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (thread != null) thread.quitSafely();
        uploads.shutdownNow();
        lastStatus = "录屏授权已结束，请重新授权";
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (handler == null) return;
        DisplayMetrics metrics = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
        handler.post(() -> resize(metrics.widthPixels, metrics.heightPixels));
    }
}

package com.xita.console;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 位置同步前台服务（持续高精度版）：
 * - 持续监听 GPS + 网络定位（约 1 秒一次），节流上报（最快 5 秒一条）；
 * - 附带高度、精度、速度；经纬度双格式（十进制度 8 位小数 + 度分秒）；
 * - 每 30 分钟上报一次手机状态（电量/网络/日程）+ 心跳诊断；
 * - 开机自启由 BootReceiver 负责；START_STICKY 保证被回收后重建；
 * - 任务被划掉：立刻重拉 + 1.5 秒精确闹钟复活；另有 5 分钟心跳守护闹钟兜底；
 * - 持 PARTIAL_WAKE_LOCK：防息屏后 CPU 深度休眠导致服务“停摆”（持续定位/通道必需）。
 */
public class LocationService extends Service {

    private static final String API = "https://112.74.84.185.nip.io/api/location/report";
    private static final String PHONE_API = "https://112.74.84.185.nip.io/api/phone/status";
    private static final String KEY = "xita-loc-2026";
    private static final long POST_MIN_GAP_MS = 1000L;
    private static final long UPDATE_MIN_TIME_MS = 1000L;
    private static final long STATUS_INTERVAL_MS = 30 * 60 * 1000L;
    private static final String CHANNEL_ID = "xita_guard";
    /** MainActivity（权限授予后）或前端桥可发这个 action 让服务立刻上报一次 */
    public static final String ACTION_REPORT_NOW = "com.xita.console.REPORT_NOW";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable statusTask;
    private Runnable channelTask;
    private long svcStartMs = 0;
    private long lastPostMs = 0;
    private volatile Location lastFix = null;
    private LocationListener listener;
    private GnssStatus.Callback gnssCb;
    private static volatile String gnssJson = "";
    private static volatile long gnssTs = 0;
    /** 唤醒锁持有标记（供远程体检） */
    public static volatile boolean wlHeld = false;
    private android.os.PowerManager.WakeLock wakeLock;
    private boolean screenOn = true;
    private volatile long pollMs = 3000L;
    private android.content.BroadcastReceiver screenRx;

    private boolean wlEnabled() {
        return getSharedPreferences("xita", MODE_PRIVATE).getBoolean("wl_enabled", true);
    }

    private void acquireWl(long timeoutMs) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "xita:location");
                    wakeLock.setReferenceCounted(false);
                }
                if (timeoutMs > 0) {
                    wakeLock.acquire(timeoutMs);
                } else if (!wakeLock.isHeld()) {
                    wakeLock.acquire();
                }
                wlHeld = true;
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseWl() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
        wlHeld = false;
    }

    /** 屏幕状态 → 电源策略：亮屏全速+锁；灭屏降频+放锁（“装乖”保命） */
    private void applyPowerMode() {
        try {
            long iv = screenOn ? 1000L : 30000L;
            pollMs = screenOn ? 3000L : 30000L;
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null && listener != null) {
                try {
                    lm.removeUpdates(listener);
                } catch (Exception ignored) {
                }
                try {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, iv, 0f, listener, Looper.getMainLooper());
                } catch (Exception ignored) {
                }
                try {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, iv, 0f, listener, Looper.getMainLooper());
                } catch (Exception ignored) {
                }
            }
            if (screenOn && wlEnabled()) {
                releaseWl();
                acquireWl(0);
            } else {
                releaseWl();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        svcStartMs = System.currentTimeMillis();
        try {
            startForeground(1, buildNotification());
        } catch (Exception ignored) {
        }
        // 屏幕感知的电源策略：亮屏全速+唤醒锁；灭屏降频+释放锁（装乖保命）
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                screenOn = pm.isInteractive();
            }
        } catch (Exception ignored) {
        }
        try {
            screenRx = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    String a = i == null ? null : i.getAction();
                    if (Intent.ACTION_SCREEN_ON.equals(a)) {
                        screenOn = true;
                    } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                        screenOn = false;
                    }
                    applyPowerMode();
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenRx, f);
        } catch (Exception ignored) {
        }
        installCrashCatcher();
        // 心跳诊断：服务启动即上报一次（区分「服务没跑」和「权限没给」）
        reportHeartbeat("service_started");
        startLocationUpdates();
        applyPowerMode();
        statusTask = new Runnable() {
            @Override
            public void run() {
                reportHeartbeat(hasLocationPermission()
                        ? "alive"
                        : ("no_permission|up=" + ((System.currentTimeMillis() - svcStartMs) / 1000) + "s"));
                reportPhoneStatus();
                handler.postDelayed(this, STATUS_INTERVAL_MS);
            }
        };
        handler.postDelayed(statusTask, STATUS_INTERVAL_MS);
        // 手机能力通道：每 3 秒拉取一次服务器指令并执行
        channelTask = new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        PhoneChannel.pollOnce(LocationService.this);
                    }
                }).start();
                handler.postDelayed(this, pollMs);
            }
        };
        handler.postDelayed(channelTask, pollMs);
    }

    private void startLocationUpdates() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return;
            }
            listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location != null) {
                        lastFix = location;
                        maybePost(location);
                    }
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_MIN_TIME_MS, 0f,
                        listener, Looper.getMainLooper());
            } catch (Exception ignored) {
            }
            try {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, UPDATE_MIN_TIME_MS, 0f,
                        listener, Looper.getMainLooper());
            } catch (Exception ignored) {
            }
            try {
                gnssCb = new GnssStatus.Callback() {
                    @Override
                    public void onSatelliteStatusChanged(GnssStatus status) {
                        updateGnss(status);
                    }
                };
                lm.registerGnssStatusCallback(gnssCb, handler);
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
    }

    /** GNSS 卫星状态快照（供前端桥读取） */
    private void updateGnss(GnssStatus status) {
        try {
            int n = status.getSatelliteCount();
            int used = 0;
            StringBuilder sb = new StringBuilder();
            sb.append("{\"ok\":true,\"ts\":").append(System.currentTimeMillis())
              .append(",\"visible\":").append(n).append(",\"sats\":[");
            int written = 0;
            for (int i = 0; i < n; i++) {
                boolean u = status.usedInFix(i);
                if (u) used++;
                if (written > 0) sb.append(',');
                int con = status.getConstellationType(i);
                String conName = con == GnssStatus.CONSTELLATION_GPS ? "GPS"
                        : con == GnssStatus.CONSTELLATION_BEIDOU ? "BDS"
                        : con == GnssStatus.CONSTELLATION_GLONASS ? "GLO"
                        : con == GnssStatus.CONSTELLATION_GALILEO ? "GAL"
                        : con == GnssStatus.CONSTELLATION_QZSS ? "QZS"
                        : con == GnssStatus.CONSTELLATION_SBAS ? "SBA" : "OTH";
                sb.append("{\"sv\":").append(status.getSvid(i))
                  .append(",\"con\":\"").append(conName).append("\"")
                  .append(",\"el\":").append(Math.round(status.getElevationDegrees(i) * 10.0) / 10.0)
                  .append(",\"az\":").append(Math.round(status.getAzimuthDegrees(i) * 10.0) / 10.0)
                  .append(",\"snr\":").append(status.getCn0DbHz(i) > 0 ? Math.round(status.getCn0DbHz(i) * 10.0) / 10.0 : -1.0)
                  .append(",\"used\":").append(u).append('}');
                written++;
            }
            sb.append("],\"used\":").append(used).append('}');
            gnssJson = sb.toString();
            gnssTs = System.currentTimeMillis();
        } catch (Exception ignored) {
        }
    }

    /** 卫星快照 JSON；超过 15 秒未更新视为失效 */
    public static String getGnssJson() {
        if (gnssJson.isEmpty() || System.currentTimeMillis() - gnssTs > 15000) {
            return "{\"ok\":false,\"stale\":true}";
        }
        return gnssJson;
    }

    private void maybePost(Location loc) {
        long now = System.currentTimeMillis();
        if (lastPostMs != 0 && now - lastPostMs < POST_MIN_GAP_MS) {
            return;
        }
        lastPostMs = now;
        final Location l = loc;
        new Thread(new Runnable() {
            @Override
            public void run() {
                post(l);
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!screenOn && wlEnabled()) {
            acquireWl(60000);
        } else {
            applyPowerMode();
        }
        if (intent != null && ACTION_REPORT_NOW.equals(intent.getAction())) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Location best = bestLastKnown();
                    if (best != null) {
                        post(best);
                    } else {
                        reportHeartbeat("report_now_no_fix");
                    }
                    reportPhoneStatus();
                }
            }).start();
        } else {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Location best = bestLastKnown();
                    if (best != null) {
                        post(best);
                    }
                }
            }).start();
        }
        scheduleWatchdog();
        return START_STICKY;
    }

    /** 供 MainActivity（权限授予后）或前端桥调用：立即上报一次 */
    public static void triggerNow(Context ctx) {
        try {
            Intent i = new Intent(ctx, LocationService.class);
            i.setAction(ACTION_REPORT_NOW);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception ignored) {
        }
    }

    private Location bestLastKnown() {
        if (lastFix != null) {
            return lastFix;
        }
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return null;
            }
            Location best = null;
            String[] providers = new String[]{
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
            };
            for (String p : providers) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) {
                        best = l;
                    }
                } catch (Exception ignored) {
                }
            }
            return best;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 最近一次上报结果（诊断用，App 内可读） */
    private void saveLastReport(String what, int code) {
        try {
            getSharedPreferences("xita", MODE_PRIVATE).edit()
                    .putString("last_report", what + ":" + code + "@" + System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    /** 任务被划掉后：立刻重拉 + 1.5 秒精确闹钟再拉一次（对抗“肌肉记忆划后台”） */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        reportHeartbeat("task_removed");
        try {
            startForegroundService(new Intent(getApplicationContext(), LocationService.class));
        } catch (Exception ignored) {
        }
        try {
            Intent restartIntent = new Intent(getApplicationContext(), LocationService.class);
            android.app.PendingIntent pi = restartPendingIntent(restartIntent, 1);
            android.app.AlarmManager am =
                    (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                long at = System.currentTimeMillis() + 1500;
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(android.app.AlarmManager.RTC, at, pi);
                } else {
                    am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC, at, pi);
                }
            }
        } catch (Exception ignored) {
        }
        super.onTaskRemoved(rootIntent);
    }

    /** 复活专用 PendingIntent：API 26+ 以「前台服务」方式拉起，成功率更高 */
    private android.app.PendingIntent restartPendingIntent(Intent intent, int reqCode) {
        int flags = android.app.PendingIntent.FLAG_ONE_SHOT
                | android.app.PendingIntent.FLAG_IMMUTABLE;
        if (Build.VERSION.SDK_INT >= 26) {
            return android.app.PendingIntent.getForegroundService(this, reqCode, intent, flags);
        }
        return android.app.PendingIntent.getService(this, reqCode, intent, flags);
    }

    /** 心跳守护：每 5 分钟定一个「唤醒自己」的闹钟；哪怕被回收，它也会把我拉回来 */
    private void scheduleWatchdog() {
        try {
            android.app.AlarmManager am =
                    (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            Intent i = new Intent(getApplicationContext(), LocationService.class);
            android.app.PendingIntent pi = restartPendingIntent(i, 2);
            long at = System.currentTimeMillis() + 5 * 60 * 1000L;
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC, at, pi);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        reportHeartbeat("service_destroyed");
        releaseWl();
        try {
            if (screenRx != null) {
                unregisterReceiver(screenRx);
            }
        } catch (Exception ignored) {
        }
        if (statusTask != null) {
            handler.removeCallbacks(statusTask);
        }
        if (channelTask != null) {
            handler.removeCallbacks(channelTask);
        }
        try {
            if (listener != null) {
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    lm.removeUpdates(listener);
                }
            }
        } catch (Exception ignored) {
        }
        try {
            if (gnssCb != null) {
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    lm.unregisterGnssStatusCallback(gnssCb);
                }
            }
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                try {
                    nm.deleteNotificationChannel("xita_location");
                } catch (Exception ignored) {
                }
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "后台守护",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("西塔")
                .setContentText("正在持续高精度定位 · 点此打开控制台")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(android.app.PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class),
                        android.app.PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build();
    }

    private boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** 度分秒格式（秒保留 4 位小数） */
    private static String fmtDms(double v, boolean isLat) {
        char dir = v >= 0 ? (isLat ? 'N' : 'E') : (isLat ? 'S' : 'W');
        double abs = Math.abs(v);
        int deg = (int) Math.floor(abs);
        double minF = (abs - deg) * 60.0;
        int min = (int) Math.floor(minF);
        double sec = (minF - min) * 60.0;
        return String.format(Locale.US, "%d°%02d′%07.4f″%c", deg, min, sec, dir);
    }

    private void post(Location loc) {
        HttpURLConnection conn = null;
        try {
            double lat = loc.getLatitude();
            double lon = loc.getLongitude();
            StringBuilder body = new StringBuilder();
            body.append("{\"lat\":").append(lat).append(",\"lon\":").append(lon);
            if (loc.hasAltitude()) {
                body.append(",\"alt\":").append(loc.getAltitude());
            }
            if (loc.hasAccuracy()) {
                body.append(",\"acc\":").append(loc.getAccuracy());
            }
            if (loc.hasSpeed()) {
                body.append(",\"spd\":").append(loc.getSpeed());
            }
            body.append(",\"fix_ms\":").append(loc.getTime());
            if (loc.getProvider() != null) {
                body.append(",\"provider\":\"").append(loc.getProvider()).append("\"");
            }
            body.append(",\"dms\":\"").append(fmtDms(lat, true)).append(" ")
                    .append(fmtDms(lon, false)).append("\"");
            body.append(",\"dec8\":\"").append(String.format(Locale.US, "%.8f", lat))
                    .append(", ").append(String.format(Locale.US, "%.8f", lon)).append("\"");
            body.append("}");

            URL url = new URL(API);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Xita-Key", KEY);
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            saveLastReport("location", conn.getResponseCode());
        } catch (Exception ignored) {
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 黑匣子：未捕获异常 → 写文件 + 上报诊断（找“静默死亡”真凶） */
    private void installCrashCatcher() {
        try {
            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    try {
                        java.io.StringWriter sw = new java.io.StringWriter();
                        e.printStackTrace(new java.io.PrintWriter(sw));
                        String s = sw.toString();
                        if (s.length() > 2500) {
                            s = s.substring(0, 2500);
                        }
                        String line = "crash:" + e.getClass().getSimpleName() + "@" + t.getName() + ":" + s;
                        try {
                            java.io.File dir = new java.io.File(
                                    android.os.Environment.getExternalStorageDirectory(), "xita_logs");
                            if (!dir.exists()) {
                                dir.mkdirs();
                            }
                            java.io.FileWriter fw = new java.io.FileWriter(
                                    new java.io.File(dir, "crash_" + System.currentTimeMillis() + ".txt"));
                            fw.write(line);
                            fw.close();
                        } catch (Exception ignored) {
                        }
                        reportHeartbeat(line.replace("\n", " | ").replace("\"", "'"));
                    } catch (Exception ignored) {
                    }
                    if (prev != null) {
                        prev.uncaughtException(t, e);
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }

    /** 心跳：只报诊断状态（无坐标），服务器日志可见 */
    private void reportHeartbeat(final String diag) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(API);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("X-Xita-Key", KEY);
                    String body = "{\"lat\":0,\"lon\":0,\"diag\":\"" + diag + "\"}";
                    OutputStream os = conn.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.close();
                    saveLastReport("hb_" + diag, conn.getResponseCode());
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
        }).start();
    }

    /** 手机状态：电量/充电/网络/今日日程（未授权日历时自动跳过） */
    private void reportPhoneStatus() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String body = buildStatusJson();
                    URL url = new URL(PHONE_API);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("X-Xita-Key", KEY);
                    OutputStream os = conn.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.close();
                    conn.getResponseCode();
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
        }).start();
    }

    private String buildStatusJson() {
        int bat = -1;
        boolean charging = false;
        try {
            android.content.Intent batt = registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (batt != null) {
                int level = batt.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = batt.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) {
                    bat = level * 100 / scale;
                }
                int st = batt.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                        || st == android.os.BatteryManager.BATTERY_STATUS_FULL;
            }
        } catch (Exception ignored) {
        }
        String net = "未知";
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && cm.getActiveNetwork() != null) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (nc != null) {
                    if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                        net = "WiFi";
                    } else if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        net = "移动数据";
                    } else if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        net = "有线";
                    }
                }
            }
        } catch (Exception ignored) {
        }
        StringBuilder cal = new StringBuilder("[");
        try {
            if (Build.VERSION.SDK_INT < 23
                    || checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
                        == PackageManager.PERMISSION_GRANTED) {
                java.util.Calendar start = java.util.Calendar.getInstance();
                start.set(java.util.Calendar.HOUR_OF_DAY, 0);
                start.set(java.util.Calendar.MINUTE, 0);
                start.set(java.util.Calendar.SECOND, 0);
                java.util.Calendar end = (java.util.Calendar) start.clone();
                end.add(java.util.Calendar.DAY_OF_YEAR, 1);
                android.database.Cursor cur = getContentResolver().query(
                        android.provider.CalendarContract.Events.CONTENT_URI,
                        new String[]{android.provider.CalendarContract.Events.TITLE,
                                android.provider.CalendarContract.Events.DTSTART},
                        android.provider.CalendarContract.Events.DTSTART + " >= ? AND "
                                + android.provider.CalendarContract.Events.DTSTART + " < ?",
                        new String[]{String.valueOf(start.getTimeInMillis()),
                                String.valueOf(end.getTimeInMillis())},
                        android.provider.CalendarContract.Events.DTSTART + " ASC");
                boolean first = true;
                while (cur != null && cur.moveToNext() && cal.length() < 400) {
                    String t = cur.getString(0);
                    long ds = cur.getLong(1);
                    java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm");
                    if (!first) {
                        cal.append(",");
                    }
                    cal.append("\"").append(escJson(fmt.format(new java.util.Date(ds)) + " "
                            + (t == null ? "" : t))).append("\"");
                    first = false;
                }
                if (cur != null) {
                    cur.close();
                }
            }
        } catch (Exception ignored) {
        }
        cal.append("]");
        return "{\"battery\":" + bat + ",\"charging\":" + charging
                + ",\"network\":\"" + escJson(net) + "\",\"calendar\":" + cal + "}";
    }

    private String escJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}

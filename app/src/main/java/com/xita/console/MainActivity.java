package com.xita.console;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

public class MainActivity extends Activity {

    private static final String APP_URL = "https://112.74.84.185.nip.io/";
    private static final int REQUEST_FILE_CHOOSER = 2001;
    private WebView webView;
    private android.webkit.ValueCallback<android.net.Uri[]> mFilePathCallback;
    private volatile float heading = -1f;
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private final SensorEventListener sensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            try {
                if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                    float[] rot = new float[9];
                    float[] ori = new float[3];
                    SensorManager.getRotationMatrixFromVector(rot, event.values);
                    SensorManager.getOrientation(rot, ori);
                    heading = (float) ((Math.toDegrees(ori[0]) + 360.0) % 360.0);
                } else if (event.sensor.getType() == Sensor.TYPE_ORIENTATION) {
                    heading = (event.values[0] + 360f) % 360f;
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };
    // 一键更新下载状态（0=空闲 2=下载中 8=完成 16=失败）
    private static volatile int upStatus = 0;
    private static volatile long upDownloaded = 0;
    private static volatile long upTotal = 0;
    private static volatile long upSpeed = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 定位权限（首次打开弹一次）+ 启动位置同步前台服务
        if (Build.VERSION.SDK_INT >= 23) {
            java.util.ArrayList<String> need = new java.util.ArrayList<String>();
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
                need.add(android.Manifest.permission.ACCESS_COARSE_LOCATION);
            }
            if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(android.Manifest.permission.READ_CALENDAR);
            }
            for (String extra : new String[]{android.Manifest.permission.CAMERA,
                    android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.READ_SMS}) {
                if (checkSelfPermission(extra) != PackageManager.PERMISSION_GRANTED) {
                    need.add(extra);
                }
            }
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(android.Manifest.permission.POST_NOTIFICATIONS);
            }
            if (!need.isEmpty()) {
                requestPermissions(need.toArray(new String[0]), 100);
            }
        }
        // 通知使用权引导（首次未开启时跳系统设置）
        try {
            if (!isNotificationListenerEnabled()) {
                android.widget.Toast.makeText(this,
                        "请在列表中开启「西塔通知同步」，她才能知道你收到了谁的消息",
                        android.widget.Toast.LENGTH_LONG).show();
                startActivity(new Intent(
                        "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            }
        } catch (Exception ignored) {
        }
        // 引导（每次启动最多弹一个）：①所有文件访问 → ②省电豁免（弹到允许为止）→ ③自启动
        try {
            boolean afOk = Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            boolean battOk = pm == null || Build.VERSION.SDK_INT < 23
                    || pm.isIgnoringBatteryOptimizations(getPackageName());
            if (!afOk
                    && !getSharedPreferences("xita", MODE_PRIVATE).getBoolean("asked_af", false)) {
                getSharedPreferences("xita", MODE_PRIVATE).edit().putBoolean("asked_af", true).apply();
                android.widget.Toast.makeText(this,
                        "请允许「所有文件访问」，西塔才能管理手机文件",
                        android.widget.Toast.LENGTH_LONG).show();
                startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
            } else if (!battOk) {
                // 弹到允许为止：前两次弹一键窗口，之后转手动列表
                int battAsk = getSharedPreferences("xita", MODE_PRIVATE).getInt("batt_ask_n", 0);
                getSharedPreferences("xita", MODE_PRIVATE).edit().putInt("batt_ask_n", battAsk + 1).apply();
                if (battAsk < 2) {
                    startActivity(new Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:" + getPackageName())));
                } else {
                    android.widget.Toast.makeText(this,
                            "请在列表里找到「西塔」→ 选「允许」",
                            android.widget.Toast.LENGTH_LONG).show();
                    startActivity(new Intent(
                            android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            } else if (pm != null
                    && !getSharedPreferences("xita", MODE_PRIVATE).getBoolean("asked_autostart", false)) {
                boolean opened = false;
                // ① 动态扫描候选包，找名字带「startup」的设置页（适配新 ROM 的入口改名）
                String[] scanPkgs = {"com.oplus.battery", "com.android.settings", "com.coloros.securityguard",
                        "com.oplus.safecenter", "com.coloros.safecenter", "com.oplus.phonemanager"};
                for (int pi2 = 0; pi2 < scanPkgs.length && !opened; pi2++) {
                    try {
                        android.content.pm.PackageInfo pinfo = getPackageManager()
                                .getPackageInfo(scanPkgs[pi2], android.content.pm.PackageManager.GET_ACTIVITIES);
                        if (pinfo.activities == null) {
                            continue;
                        }
                        for (android.content.pm.ActivityInfo act : pinfo.activities) {
                            if (act.name == null
                                    || !act.name.toLowerCase(java.util.Locale.US).contains("startup")) {
                                continue;
                            }
                            Intent si = new Intent();
                            si.setComponent(new android.content.ComponentName(scanPkgs[pi2], act.name));
                            if (getPackageManager().resolveActivity(si, 0) == null) {
                                continue;
                            }
                            si.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(si);
                            opened = true;
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
                // ② 固定候选兜底（含耗电管理页）
                if (!opened) {
                    String[][] ca = {
                            {"com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity"},
                            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                            {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                            {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                            {"com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"},
                            {"com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"},
                            {"com.oplus.phonemanager", "com.oplus.phonemanager.startupapp.StartupAppListActivity"},
                            {"com.coloros.phonemanager", "com.coloros.phonemanager.startupapp.StartupAppListActivity"},
                            {"com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"},
                    };
                    for (String[] c : ca) {
                        try {
                            Intent ai = new Intent();
                            ai.setComponent(new android.content.ComponentName(c[0], c[1]));
                            if (getPackageManager().resolveActivity(ai, 0) == null) {
                                continue;
                            }
                            ai.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(ai);
                            opened = true;
                            break;
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (opened) {
                    getSharedPreferences("xita", MODE_PRIVATE).edit().putBoolean("asked_autostart", true).apply();
                    android.widget.Toast.makeText(this,
                            "找到「西塔」→ 打开自启动开关（列表里没有就去设置搜「自启动」）",
                            android.widget.Toast.LENGTH_LONG).show();
                } else {
                    int n = getSharedPreferences("xita", MODE_PRIVATE).getInt("autostart_try_n", 0) + 1;
                    getSharedPreferences("xita", MODE_PRIVATE).edit().putInt("autostart_try_n", n).apply();
                    if (n >= 3) {
                        getSharedPreferences("xita", MODE_PRIVATE).edit().putBoolean("asked_autostart", true).apply();
                    }
                    android.widget.Toast.makeText(this,
                            "去设置搜「自启动」→ 自启动管理 → 允许西塔（另：应用→西塔→耗电管理→允许完全后台行为）",
                            android.widget.Toast.LENGTH_LONG).show();
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) {
                    }
                }
            } else if (!getSharedPreferences("xita", MODE_PRIVATE).getBoolean("asked_frozen", false)) {
                boolean fOpened = false;
                // 找「应用冻结」设置页（真我 UI 自带 AppFrozen 页面）
                try {
                    android.content.pm.PackageInfo pinfo2 = getPackageManager()
                            .getPackageInfo("com.android.settings", android.content.pm.PackageManager.GET_ACTIVITIES);
                    if (pinfo2.activities != null) {
                        for (android.content.pm.ActivityInfo act : pinfo2.activities) {
                            if (act.name == null
                                    || !act.name.toLowerCase(java.util.Locale.US).contains("frozen")) {
                                continue;
                            }
                            Intent fi = new Intent();
                            fi.setComponent(new android.content.ComponentName("com.android.settings", act.name));
                            if (getPackageManager().resolveActivity(fi, 0) == null) {
                                continue;
                            }
                            fi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(fi);
                            fOpened = true;
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
                if (!fOpened) {
                    try {
                        Intent fi = new Intent();
                        fi.setComponent(new android.content.ComponentName("com.oplus.battery",
                                "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"));
                        fi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(fi);
                        fOpened = true;
                    } catch (Exception ignored) {
                    }
                }
                getSharedPreferences("xita", MODE_PRIVATE).edit().putBoolean("asked_frozen", true).apply();
                android.widget.Toast.makeText(this,
                        "找到「西塔」→ 关掉「后台冻结」、允许「后台活动」（没有就搜『冻结』）",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (Exception ignored) {
        }
        // 最近任务锁定提示（一次性）：卡片加锁后，划后台/一键清理都动不了它
        try {
            android.os.PowerManager pm3 = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm3 != null && pm3.isIgnoringBatteryOptimizations(getPackageName())
                    && !getSharedPreferences("xita", MODE_PRIVATE).getBoolean("lock_tip", false)) {
                getSharedPreferences("xita", MODE_PRIVATE).edit().putBoolean("lock_tip", true).apply();
                android.widget.Toast.makeText(this,
                        "最后一步：最近任务里找到西塔卡片 → 卡片菜单 → 「锁定」",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (Exception ignored) {
        }
        try {
            Intent locSvc = new Intent(this, LocationService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(locSvc);
            } else {
                startService(locSvc);
            }
        } catch (Exception ignored) {
        }

        // 内置画图引擎：有模型就拉起（同配置幂等）
        EngineBridge.ensureEngine(this);

        // 状态栏：深色背景
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.parseColor("#1a1a2e"));
            window.setNavigationBarColor(Color.parseColor("#1a1a2e"));
        }

        webView = new WebView(this);
        setContentView(webView);
        // 一键更新桥（前端检测到 window.XitaApp 时走原生下载+安装）
        webView.addJavascriptInterface(new UpdateBridge(), "XitaApp");

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setAllowFileAccess(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        // 文件选择器（文件页「上传」按钮需要；默认 WebChromeClient 不处理 → 点击无反应）
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v,
                    android.webkit.ValueCallback<android.net.Uri[]> filePathCallback,
                    FileChooserParams params) {
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                }
                mFilePathCallback = filePathCallback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, REQUEST_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    mFilePathCallback = null;
                    return false;
                }
            }
        });

        // 返回键：优先回网页上一页
        webView.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
                    webView.goBack();
                    return true;
                }
                return false;
            }
        });

        // 下载：存到系统下载目录
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    String filename = URLUtil.guessFileName(url, contentDisposition, mimetype);
                    request.setMimeType(mimetype);
                    request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    // Android 10+ 分区存储：公共目录需存储权限，改私有目录（下载通知里可直接点开）
                    request.setDestinationInExternalFilesDir(MainActivity.this,
                            Environment.DIRECTORY_DOWNLOADS, filename);
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    dm.enqueue(request);
                } catch (Exception ignored) {
                }
            }
        });

        loadUrl();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_FILE_CHOOSER) {
            if (mFilePathCallback != null) {
                android.net.Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new android.net.Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new android.net.Uri[]{data.getData()};
                    }
                }
                mFilePathCallback.onReceiveValue(results);
                mFilePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void loadUrl() {
        String url = APP_URL;
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            url = APP_URL + "?apkver=" + pi.versionName + "&apkbuild=" + pi.versionCode;
        } catch (Exception ignored) {
        }
        webView.loadUrl(url);
    }

    /** 一键更新桥：原生下载 APK 并自动拉起系统安装器 */
    public class UpdateBridge {

        /** 卫星快照（JSON 字符串） */
        @android.webkit.JavascriptInterface
        public String getGnss() {
            return LocationService.getGnssJson();
        }

        /** 磁方向角（度）；传感器不可用时返回空串 */
        @android.webkit.JavascriptInterface
        public String getHeading() {
            return heading >= 0 ? String.format(java.util.Locale.US, "%.1f", heading) : "";
        }

        /** 复制文本到系统剪贴板 */
        @android.webkit.JavascriptInterface
        public void copyText(final String text) {
            try {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null && text != null) {
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("xita", text));
                            }
                        } catch (Exception ignored) {
                        }
                    }
                });
            } catch (Exception ignored) {
            }
        }

        @android.webkit.JavascriptInterface
        public void installUpdate(final String url) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                        upStatus = 0;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    android.widget.Toast.makeText(MainActivity.this,
                                            "请先允许「安装未知应用」，然后回来再点一次更新",
                                            android.widget.Toast.LENGTH_LONG).show();
                                    Intent st = new Intent(
                                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            android.net.Uri.parse("package:" + getPackageName()));
                                    st.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    startActivity(st);
                                } catch (Exception ignored) {
                                }
                            }
                        });
                        return;
                    }
                    java.net.HttpURLConnection conn = null;
                    android.content.pm.PackageInstaller.Session session = null;
                    try {
                        upStatus = 2;
                        upDownloaded = 0;
                        upTotal = 0;
                        upSpeed = 0;
                        java.net.URL u = new java.net.URL(url);
                        conn = (java.net.HttpURLConnection) u.openConnection();
                        conn.setConnectTimeout(15000);
                        conn.setReadTimeout(60000);
                        conn.setInstanceFollowRedirects(true);
                        conn.connect();
                        upTotal = conn.getContentLength();

                        android.content.pm.PackageInstaller pi =
                                getPackageManager().getPackageInstaller();
                        android.content.pm.PackageInstaller.SessionParams params =
                                new android.content.pm.PackageInstaller.SessionParams(
                                        android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                        int sessionId = pi.createSession(params);
                        session = pi.openSession(sessionId);

                        java.io.InputStream in = conn.getInputStream();
                        java.io.OutputStream out = session.openWrite("xita-update.apk", 0, upTotal);
                        byte[] buf = new byte[65536];
                        int n;
                        long lastT = System.currentTimeMillis();
                        long lastB = 0;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            upDownloaded += n;
                            long now = System.currentTimeMillis();
                            if (now - lastT >= 500) {
                                upSpeed = (upDownloaded - lastB) * 1000 / (now - lastT);
                                lastT = now;
                                lastB = upDownloaded;
                            }
                        }
                        out.flush();
                        session.fsync(out);
                        out.close();
                        in.close();

                        int piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
                        if (Build.VERSION.SDK_INT >= 31) {
                            // 系统需把安装结果 extras 写进这个广播，必须 mutable（immutable 会丢掉，安装界面不弹）
                            piFlags |= android.app.PendingIntent.FLAG_MUTABLE;
                        }
                        android.app.PendingIntent pending = android.app.PendingIntent.getBroadcast(
                                MainActivity.this, sessionId,
                                new Intent(MainActivity.this, InstallReceiver.class)
                                        .setAction("com.xita.console.INSTALL_RESULT"),
                                piFlags);
                        session.commit(pending.getIntentSender());
                        upStatus = 8;
                    } catch (Exception e) {
                        upStatus = 16;
                    } finally {
                        try {
                            if (session != null) {
                                session.close();
                            }
                        } catch (Exception ignored) {
                        }
                        if (conn != null) {
                            conn.disconnect();
                        }
                    }
                }
            }).start();
        }

        /** 下载进度（前端每秒轮询） */
        @android.webkit.JavascriptInterface
        public String getUpdateProgress() {
            return "{\"status\":" + upStatus + ",\"downloaded\":" + upDownloaded
                    + ",\"total\":" + upTotal + ",\"speed\":" + upSpeed + "}";
        }
    }

    @Override
    protected void onPause() {
        try {
            if (sensorManager != null) {
                sensorManager.unregisterListener(sensorListener);
            }
        } catch (Exception ignored) {
        }
        super.onPause();
        webView.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            // 权限刚授予 → 让位置服务立刻上报一次
            LocationService.triggerNow(this);
            // Android 10+：引导授予「始终允许」定位（锁屏/后台持续定位）
            if (Build.VERSION.SDK_INT >= 29
                    && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && !getSharedPreferences("xita", MODE_PRIVATE).getBoolean("asked_bg_loc", false)) {
                getSharedPreferences("xita", MODE_PRIVATE).edit().putBoolean("asked_bg_loc", true).apply();
                try {
                    android.widget.Toast.makeText(this,
                            "请选择「始终允许」定位，锁屏后也能持续记录位置",
                            android.widget.Toast.LENGTH_LONG).show();
                    requestPermissions(new String[]{android.Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 101);
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        try {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null && rotationSensor == null) {
                rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
                if (rotationSensor == null) {
                    rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);
                }
            }
            if (sensorManager != null && rotationSensor != null) {
                sensorManager.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_UI);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }

    private boolean isNotificationListenerEnabled() {
        try {
            String flat = android.provider.Settings.Secure.getString(
                    getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }
}

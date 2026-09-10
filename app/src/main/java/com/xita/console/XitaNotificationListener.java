package com.xita.console;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 通知摘要监听：只上报「App 名 + 标题（截断40字，验证码数字打码）」，不上传正文。
 * 批量上报（攒 5 条或 10 分钟一次），减少网络请求。
 */
public class XitaNotificationListener extends NotificationListenerService {

    private static final String API = "https://112.74.84.185.nip.io/api/phone/notifications";
    private static final String KEY = "xita-loc-2026";
    private static final List<String> pending = new ArrayList<String>();
    private static long lastFlush = 0;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null) {
                return;
            }
            String pkg = sbn.getPackageName();
            if (pkg == null || pkg.equals(getPackageName())) {
                return;
            }
            // 忽略常驻/低优先级通知（前台服务等）
            Notification n = sbn.getNotification();
            if (n == null) {
                return;
            }
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
                return;
            }
            Bundle extras = n.extras;
            CharSequence titleCs = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence textCs = extras.getCharSequence(Notification.EXTRA_TEXT);
            String title = titleCs == null ? "" : titleCs.toString();
            if (title.trim().isEmpty() && textCs != null) {
                title = textCs.toString();
            }
            // 隐私过滤：连续 4-8 位数字（验证码/账号）打码；截断 40 字
            title = title.replaceAll("\\d{4,8}", "****").trim();
            if (title.length() > 40) {
                title = title.substring(0, 40);
            }
            String appName = appLabel(pkg);
            String rec = "{\"app\":\"" + esc(appName) + "\",\"title\":\"" + esc(title) + "\",\"ts\":" + System.currentTimeMillis() + "}";
            synchronized (pending) {
                pending.add(rec);
                long now = System.currentTimeMillis();
                if (pending.size() >= 5 || now - lastFlush > 10 * 60 * 1000L) {
                    flushLocked();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void flushLocked() {
        if (pending.isEmpty()) {
            return;
        }
        final String body = "{\"items\":[" + join(pending) + "]}";
        pending.clear();
        lastFlush = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(API);
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

    private String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private String appLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            if (label != null) {
                return label.toString();
            }
        } catch (Exception ignored) {
        }
        return pkg;
    }

    private String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}

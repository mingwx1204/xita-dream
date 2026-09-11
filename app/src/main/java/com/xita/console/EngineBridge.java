package com.xita.console;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 画图引擎桥（引擎内核已并入本应用）：模型管理 + 服务拉起 + 远程下载 */
public class EngineBridge {

    private static final String TAG = "EngineBridge";
    /** 引擎后端服务（Kotlin 类，同模块内可直接按类名启动） */
    private static final String BACKEND_SERVICE = "io.github.xororz.localdream.service.BackendService";
    private static final String ACTION_STOP = "io.github.xororz.localdream.STOP_GENERATION";

    /** 引擎自动拉起的模型优先序：{modelId, backendType, width, height} */
    private static final String[][] PRIORITY = {
            {"illustrious_v16_dmd2", "sdxl", "1024", "1024"},
            {"cyber_realistic_v10_dmd2", "sdxl", "1024", "1024"},
            {"illustrious_v16", "sdxl", "1024", "1024"},
            {"cyber_realistic_v10", "sdxl", "1024", "1024"},
            {"anythingv5", "sd15npu", "512", "512"},
    };

    public static File modelsDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "models");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    public static boolean isModelReady(Context ctx, String id) {
        File d = new File(modelsDir(ctx), id);
        if (!d.isDirectory()) {
            return false;
        }
        File[] fs = d.listFiles();
        return fs != null && fs.length > 0;
    }

    /** App 打开时调用：有可用模型就拉起引擎（相同配置重复调用是幂等的） */
    public static void ensureEngine(Context ctx) {
        for (String[] row : PRIORITY) {
            if (isModelReady(ctx, row[0])) {
                startEngine(ctx, row[0], row[1],
                        Integer.parseInt(row[2]), Integer.parseInt(row[3]));
                return;
            }
        }
    }

    public static void startEngine(Context ctx, String modelId, String backendType, int w, int h) {
        try {
            Intent i = new Intent();
            i.setClassName(ctx, BACKEND_SERVICE);
            i.putExtra("modelId", modelId);
            i.putExtra("backendType", backendType);
            i.putExtra("width", w);
            i.putExtra("height", h);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            Log.i(TAG, "engine start requested: " + modelId);
        } catch (Exception e) {
            Log.e(TAG, "engine start failed: " + e);
        }
        // 顺手保证通道在，并自检一次（坏链自动重连）——她每次出笔前的唤醒都会走到这里，等于每次画画前顺一遍线
        EngineTunnel.ensureStarted();
        EngineTunnel.heal();
    }

    public static void stopEngine(Context ctx) {
        try {
            Intent i = new Intent();
            i.setClassName(ctx, BACKEND_SERVICE);
            i.setAction(ACTION_STOP);
            ctx.startService(i);
        } catch (Exception ignored) {
        }
    }

    /** 已就绪模型清单（JSON 字符串，供远程体检） */
    public static String modelsJson(Context ctx) {
        StringBuilder sb = new StringBuilder("[");
        File[] dirs = modelsDir(ctx).listFiles();
        boolean first = true;
        if (dirs != null) {
            for (File d : dirs) {
                if (!d.isDirectory()) {
                    continue;
                }
                File[] fs = d.listFiles();
                if (fs == null || fs.length == 0) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"id\":\"").append(d.getName())
                        .append("\",\"files\":").append(fs.length)
                        .append(",\"v3\":").append(new File(d, "v3").exists())
                        .append('}');
            }
        }
        return sb.append(']').toString();
    }

    // ---- 模型下载（后台线程；进度写入 dl_<id>.state）----

    public static void downloadModelAsync(final Context ctx, final String id, final String url) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File tempDir = new File(ctx.getFilesDir(), "temp_downloads");
                    tempDir.mkdirs();
                    File tmp = new File(tempDir, id + ".tmp");
                    writeState(ctx, id, "downloading 0");
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(60000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                    InputStream in = conn.getInputStream();
                    long total = conn.getContentLengthLong();
                    long got = 0;
                    long lastReport = 0;
                    byte[] buf = new byte[1 << 16];
                    int n;
                    OutputStream os = new FileOutputStream(tmp);
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        got += n;
                        long now = System.currentTimeMillis();
                        if (now - lastReport > 2000) {
                            lastReport = now;
                            writeState(ctx, id, "downloading " + got + (total > 0 ? "/" + total : ""));
                        }
                    }
                    os.close();
                    in.close();
                    writeState(ctx, id, "extracting");
                    File modelDir = new File(modelsDir(ctx), id);
                    deleteRec(modelDir);
                    modelDir.mkdirs();
                    unzip(tmp, modelDir);
                    new File(modelDir, "v3").createNewFile();
                    tmp.delete();
                    writeState(ctx, id, "done");
                    Log.i(TAG, "model ready: " + id);
                } catch (Exception e) {
                    writeState(ctx, id, "error " + e);
                    Log.e(TAG, "download fail: " + e);
                }
            }
        }).start();
    }

    public static String downloadState(Context ctx, String id) {
        try {
            File f = new File(ctx.getFilesDir(), "dl_" + id + ".state");
            if (!f.exists()) {
                return "none";
            }
            byte[] b = new byte[(int) f.length()];
            FileInputStream is = new FileInputStream(f);
            int n = is.read(b);
            is.close();
            return new String(b, 0, Math.max(n, 0), "UTF-8");
        } catch (Exception e) {
            return "err";
        }
    }

    private static void writeState(Context ctx, String id, String s) {
        try {
            FileOutputStream fos = new FileOutputStream(new File(ctx.getFilesDir(), "dl_" + id + ".state"));
            fos.write(s.getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    private static void unzip(File zip, File dest) throws Exception {
        // 与官方下载器一致：全部条目按“文件名”扁平化到顶层（丢弃目录壳）
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)));
        ZipEntry e;
        byte[] buf = new byte[1 << 16];
        while ((e = zis.getNextEntry()) != null) {
            if (!e.isDirectory()) {
                String name = e.getName();
                int slash = name.lastIndexOf('/');
                if (slash >= 0) {
                    name = name.substring(slash + 1);
                }
                if (!name.isEmpty() && !name.startsWith(".") && !name.startsWith("__MACOSX")) {
                    File out = new File(dest, name);
                    FileOutputStream fos = new FileOutputStream(out);
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                    fos.close();
                }
            }
            zis.closeEntry();
        }
        zis.close();
    }

    private static void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) {
                for (File c : fs) {
                    deleteRec(c);
                }
            }
        }
        f.delete();
    }
}

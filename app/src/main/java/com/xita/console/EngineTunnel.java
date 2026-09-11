package com.xita.console;

import android.util.Log;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

/** 内置反向隧道：服务器 127.0.0.1:18089 → 本机 127.0.0.1:8081（画图引擎），不依赖 AiCode 容器。
 *  自愈：每 90 秒经 SSH 回环做一次"真通"自测，坏链（半死连接）自动重连；engine.start 时也会自检一次。 */
public final class EngineTunnel {

    private static final String TAG = "EngineTunnel";
    private static final String HOST = "112.74.84.185";
    private static final int SSH_PORT = 22;
    private static final String USER = "root";
    private static final String PASS = "shenshuai@Q1";
    private static final int REMOTE_PORT = 18089;
    private static final int LOCAL_PORT = 8081;
    private static final long PROBE_INTERVAL_MS = 90_000L;

    private static volatile boolean started = false;
    private static volatile Session live = null;

    private EngineTunnel() {
    }

    /** 幂等启动（进程每次拉起时调用；断线自动重连由内部循环负责） */
    public static synchronized void ensureStarted() {
        if (started) {
            return;
        }
        started = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "engine-tunnel");
        t.setDaemon(true);
        t.start();
    }

    public static boolean isUp() {
        Session s = live;
        return s != null && s.isConnected();
    }

    /** 断开当前会话，逼循环立刻重连（清理半死连接） */
    public static void reset() {
        Session s = live;
        if (s != null) {
            try {
                s.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 自检+修复：探测通道是否真的可用，坏链则重连 */
    public static void heal() {
        if (!probe()) {
            Log.w(TAG, "probe failed, forcing reconnect");
            reset();
        }
    }

    /** 经 SSH 回环自测：服务器 127.0.0.1:18089 → 本机 8081，能握上手才算真通 */
    private static boolean probe() {
        Session s = live;
        if (s == null || !s.isConnected()) {
            return false;
        }
        try {
            ChannelDirectTCPIP ch = (ChannelDirectTCPIP) s.openChannel("direct-tcpip");
            ch.setHost("127.0.0.1");
            ch.setPort(REMOTE_PORT);
            ch.setOrgIPAddress("127.0.0.1");
            ch.setOrgPort(1);
            ch.connect(5000);
            ch.disconnect();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static void loop() {
        while (true) {
            Session session = null;
            try {
                JSch jsch = new JSch();
                session = jsch.getSession(USER, HOST, SSH_PORT);
                session.setPassword(PASS);
                session.setConfig("StrictHostKeyChecking", "no");
                session.setConfig("ServerAliveInterval", "15000");
                session.setConfig("ServerAliveCountMax", "2");
                session.connect(20000);
                session.setPortForwardingR(REMOTE_PORT, "127.0.0.1", LOCAL_PORT);
                live = session;
                Log.i(TAG, "tunnel up " + REMOTE_PORT + " -> " + LOCAL_PORT);
                long lastProbe = System.currentTimeMillis();
                while (session.isConnected()) {
                    Thread.sleep(5000);
                    if (System.currentTimeMillis() - lastProbe > PROBE_INTERVAL_MS) {
                        lastProbe = System.currentTimeMillis();
                        if (!probe()) {
                            Log.w(TAG, "keepalive probe failed");
                            break;
                        }
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "tunnel down: " + e);
            } finally {
                live = null;
                if (session != null) {
                    try {
                        session.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
            }
            try {
                Thread.sleep(10000);
            } catch (InterruptedException ignored) {
            }
        }
    }
}

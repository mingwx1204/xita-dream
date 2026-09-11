package com.xita.console;

import android.util.Log;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

/** 内置反向隧道：服务器 127.0.0.1:18089 → 本机 127.0.0.1:8081（画图引擎），不依赖 AiCode 容器 */
public final class EngineTunnel {

    private static final String TAG = "EngineTunnel";
    private static final String HOST = "112.74.84.185";
    private static final int SSH_PORT = 22;
    private static final String USER = "root";
    private static final String PASS = "shenshuai@Q1";
    private static final int REMOTE_PORT = 18089;
    private static final int LOCAL_PORT = 8081;

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

    private static void loop() {
        while (true) {
            Session session = null;
            try {
                JSch jsch = new JSch();
                session = jsch.getSession(USER, HOST, SSH_PORT);
                session.setPassword(PASS);
                session.setConfig("StrictHostKeyChecking", "no");
                session.setConfig("ServerAliveInterval", "30000");
                session.setConfig("ServerAliveCountMax", "3");
                session.connect(20000);
                session.setPortForwardingR(REMOTE_PORT, "127.0.0.1", LOCAL_PORT);
                live = session;
                Log.i(TAG, "tunnel up " + REMOTE_PORT + " -> " + LOCAL_PORT);
                while (session.isConnected()) {
                    Thread.sleep(5000);
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

package com.xita.console;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启：拉起位置/手机状态上报服务。
 * 国产 ROM 若禁止自启动则无效，需用户在系统设置里给「西塔」放行自启动/后台运行。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        try {
            Intent svc = new Intent(context, LocationService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception ignored) {
        }
    }
}

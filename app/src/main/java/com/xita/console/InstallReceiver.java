package com.xita.console;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** 安装结果接收（PackageInstaller commit 的回调） */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // 需要用户确认：拉起系统安装界面
                Intent confirm = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
            } else if (status == PackageInstaller.STATUS_SUCCESS) {
                Toast.makeText(context, "西塔更新完成", Toast.LENGTH_SHORT).show();
            } else {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(context, "安装失败: " + (msg == null ? "" : msg),
                        Toast.LENGTH_LONG).show();
            }
        } catch (Exception ignored) {
        }
    }
}

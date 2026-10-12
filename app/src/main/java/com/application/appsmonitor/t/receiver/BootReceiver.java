package com.application.appsmonitor.t.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.application.appsmonitor.t.service.UsageMonitorService;

/**
 * BootReceiver
 * 开机完成后拉起 {@link UsageMonitorService}，使实时时长监控在重启后自动恢复。
 * Android 8.0+ 从后台广播只能以 startForegroundService 方式启动服务，
 * 服务本身在 onCreate 中立即调用 startForeground()，满足系统约束。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            ContextCompat.startForegroundService(context,
                    new Intent(context, UsageMonitorService.class));
        }
    }
}

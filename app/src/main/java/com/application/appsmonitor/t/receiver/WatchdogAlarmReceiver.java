package com.application.appsmonitor.t.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.application.appsmonitor.t.AppConst;
import com.application.appsmonitor.t.service.UsageMonitorService;
import com.application.appsmonitor.t.util.MonitorAlarmUtil;

/**
 * WatchdogAlarmReceiver
 * 每 5 分钟由独立闹钟触发，检查 UsageMonitorService 是否存活。
 * 若发现服务已被杀（MIUI 可能批量清闹钟但不会每次都清干净），
 * 立即重新拉起前台服务 + 续排主闹钟链 + 续排自身。
 * 作为 AlarmManager 主闹钟链的"看门狗"，构成第二道保活防线。
 */
public class WatchdogAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !AppConst.WATCHDOG_ALARM_ACTION.equals(intent.getAction())) return;

        // 检查主服务是否存活
        if (!UsageMonitorService.isRunning()) {
            // 服务已被杀，重新拉起
            ContextCompat.startForegroundService(context,
                    new Intent(context, UsageMonitorService.class));
            // 同时续排主闹钟链（防止主闹钟也被清除）
            MonitorAlarmUtil.scheduleAlarm(context);
        }

        // 无论如何续排下一次 watchdog 闹钟
        MonitorAlarmUtil.scheduleWatchdog(context);
    }
}

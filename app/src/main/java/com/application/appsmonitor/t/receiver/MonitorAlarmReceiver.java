package com.application.appsmonitor.t.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.application.appsmonitor.t.AppConst;
import com.application.appsmonitor.t.service.UsageMonitorService;
import com.application.appsmonitor.t.util.MonitorAlarmUtil;

/**
 * MonitorAlarmReceiver
 * 每分钟精确闹钟到达时触发：此时进程可能已被系统杀死，
 * 广播到达即由系统拉活进程，再以 startForegroundService 拉起
 * {@link UsageMonitorService} 执行一次时长检查，并续排下一次闹钟。
 * 这是“应用不在前台也能按时弹通知”的关键链路。
 */
public class MonitorAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !AppConst.MONITOR_ALARM_ACTION.equals(intent.getAction())) return;
        Intent service = new Intent(context, UsageMonitorService.class);
        service.setAction(AppConst.MONITOR_ALARM_ACTION);
        // Android 8+ 后台广播只能通过 startForegroundService 启动服务
        ContextCompat.startForegroundService(context, service);
        // 闹钟是一次性的（setExact 系列），处理完必须续排下一分钟
        MonitorAlarmUtil.scheduleAlarm(context);
    }
}

package com.application.appsmonitor.t.util;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.application.appsmonitor.t.AppConst;
import com.application.appsmonitor.t.receiver.MonitorAlarmReceiver;

/**
 * MonitorAlarmUtil
 * 为实时时长监控调度“每分钟精确唤醒”闹钟。
 * Handler.postDelayed 在应用退到后台后会被 HyperOS 冻结/杀进程而停摆，
 * 导致“只有 App 在前台才弹提醒”；AlarmManager 的闹钟由系统进程保管，
 * setExactAndAllowWhileIdle 还能穿透 Doze 免休眠，进程被杀也照样触发。
 */
public final class MonitorAlarmUtil {

    private MonitorAlarmUtil() {
    }

    public static void scheduleAlarm(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, MonitorAlarmReceiver.class);
        i.setAction(AppConst.MONITOR_ALARM_ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i, flags);
        long next = System.currentTimeMillis() + AppConst.MONITOR_CHECK_INTERVAL_MS;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // 精确 + 免 Doze 休眠，后台/灭屏也能按时唤醒
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                am.setExact(AlarmManager.RTC_WAKEUP, next, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, next, pi);
            }
        } catch (SecurityException e) {
            // 个别 ROM 可能拒绝精确闹钟，降级为非精确，保证链路不断
            am.set(AlarmManager.RTC_WAKEUP, next, pi);
        }
    }

    public static void cancelAlarm(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, MonitorAlarmReceiver.class);
        i.setAction(AppConst.MONITOR_ALARM_ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i, flags);
        am.cancel(pi);
    }
}
